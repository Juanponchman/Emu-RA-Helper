package io.github.mayusi.emuhelper.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Cookie jar tailored for archive.org, with on-disk persistence so a logged-in
 * session survives app restarts (no re-login, no re-typing credentials).
 *
 * Archive.org sets cookies on `domain=.archive.org` and routes downloads through
 * per-request CDN hosts (e.g. `dn721009.ca.archive.org`). Three things must hold:
 *
 *  1. Cookies set for `.archive.org` must be sent to EVERY `*.archive.org` host,
 *     not just the exact host that set them. (CDN nodes need the auth cookies.)
 *  2. The login flow sets a cookie, then immediately "deletes" it in the same
 *     response by re-setting the same name with an expiry in 1970. We must MERGE
 *     by name and DROP expired cookies, otherwise the `=deleted` sentinels
 *     (and stale values from earlier requests) clobber the real auth cookies and
 *     archive.org treats us as logged-out -> 401 on restricted items.
 *  3. The auth cookies (logged-in-user/logged-in-sig) are persistent (multi-year
 *     expiry). Persisting them to disk lets us restore the session on a cold
 *     start instead of doing a fresh network login every launch.
 *
 * Stored flat (not per-host) keyed by cookie name, because all archive.org
 * cookies share the same registrable domain.
 */
class IaCookieJar(context: Context) : CookieJar {
    // name -> most recent non-expired cookie for the archive.org domain
    private val store = ConcurrentHashMap<String, Cookie>()

    // Reactive login state so the UI can update the instant the (async, off-main)
    // disk restore finishes — otherwise an early synchronous isLoggedIn() reads the
    // still-empty store on cold start and the user looks signed-out until a manual
    // re-check. Starts false; flipped after loadFromDisk() and on every mutation.
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    private fun refreshLoggedIn() { _loggedIn.value = computeHasCookies() }

    // Lazily created — touching EncryptedSharedPreferences does disk I/O and a
    // KeyStore op, so we never want it on the main thread. Both the initial load
    // and every persist run on a background thread.
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { openPrefs(appContext) }

    private fun openPrefs(ctx: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "emuhelper_cookies",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("EmuHelper", "Encrypted cookie store init failed; using plain prefs", e)
        ctx.getSharedPreferences("emuhelper_cookies_fallback", Context.MODE_PRIVATE)
    }

    init {
        // Restore the saved session off the main thread; the in-memory `store`
        // (a ConcurrentHashMap) fills in shortly after construction, and we then
        // publish the restored login state so the UI can react.
        Thread {
            loadFromDisk()
            refreshLoggedIn()
        }.apply { isDaemon = true; start() }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        var changed = false
        for (cookie in cookies) {
            if (cookie.expiresAt <= now) {
                if (store.remove(cookie.name) != null) changed = true
            } else {
                store[cookie.name] = cookie
                changed = true
            }
        }
        if (changed) { persistToDisk(); refreshLoggedIn() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val host = url.host
        val result = ArrayList<Cookie>(store.size)
        val expiredNames = ArrayList<String>()
        for ((name, cookie) in store) {
            if (cookie.expiresAt <= now) { expiredNames.add(name); continue }
            // Send a cookie if the request host matches its domain (or any subdomain of it).
            if (host == cookie.domain || host.endsWith("." + cookie.domain)) {
                result.add(cookie)
            }
        }
        if (expiredNames.isNotEmpty()) {
            expiredNames.forEach { store.remove(it) }
            persistToDisk()
            refreshLoggedIn()
        }
        return result
    }

    fun clear() {
        store.clear() // in-memory flips immediately so hasCookies() is correct now
        refreshLoggedIn()
        Thread {
            try { prefs.edit().remove(KEY_COOKIES).apply() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    /**
     * True only when we hold the cookie archive.org uses to prove a logged-in
     * session, with a real (non-"deleted") value and not yet expired.
     */
    fun hasCookies(): Boolean = computeHasCookies()

    private fun computeHasCookies(): Boolean {
        val now = System.currentTimeMillis()
        // IMPORTANT: `ia-auth` is a CSRF token that archive.org sets even on a FAILED
        // login, so it must NOT be treated as proof of a session. The real
        // logged-in cookies are `logged-in-sig` / `logged-in-user`, which appear ONLY
        // after a successful login. Require one of those.
        val authNames = listOf("logged-in-sig", "logged-in-user")
        return authNames.any { name ->
            val c = store[name]
            c != null && c.expiresAt > now && c.value.isNotBlank() && c.value != "deleted"
        }
    }

    // ---- persistence ------------------------------------------------------

    private fun persistToDisk() {
        try {
            val now = System.currentTimeMillis()
            val serialized = store.values
                .filter { it.expiresAt > now && it.persistent } // only durable cookies worth restoring
                .map { serialize(it) }
                .toSet()
            prefs.edit().putStringSet(KEY_COOKIES, serialized).apply()
        } catch (e: Exception) {
            Log.w("EmuHelper", "Persisting cookies failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val now = System.currentTimeMillis()
            val saved = prefs.getStringSet(KEY_COOKIES, emptySet()) ?: emptySet()
            for (line in saved) {
                val c = deserialize(line) ?: continue
                if (c.expiresAt > now) store[c.name] = c
            }
        } catch (e: Exception) {
            Log.w("EmuHelper", "Loading cookies failed", e)
        }
    }

    /** Fields joined by SEP (cannot appear in a cookie):
     *  name / value / expiresAt / domain / path / secure / httpOnly / hostOnly */
    private fun serialize(c: Cookie): String = listOf(
        c.name, c.value, c.expiresAt.toString(), c.domain, c.path,
        c.secure.toString(), c.httpOnly.toString(), c.hostOnly.toString()
    ).joinToString(SEP)

    private fun deserialize(s: String): Cookie? {
        val p = s.split(SEP)
        if (p.size < 8) return null
        return try {
            val builder = Cookie.Builder()
                .name(p[0])
                .value(p[1])
                .expiresAt(p[2].toLong())
                .path(p[4])
            // hostOnly cookies use .hostOnlyDomain(); domain cookies use .domain()
            if (p[7].toBoolean()) builder.hostOnlyDomain(p[3]) else builder.domain(p[3])
            if (p[5].toBoolean()) builder.secure()
            if (p[6].toBoolean()) builder.httpOnly()
            builder.build()
        } catch (e: Exception) {
            Log.w("EmuHelper", "Cookie deserialize failed", e)
            null
        }
    }

    companion object {
        private const val KEY_COOKIES = "ia_cookies_v1"
        // Delimiter that cannot occur in an archive.org cookie field (name/value/
        // domain/path use [A-Za-z0-9._/=-]); this triple token is collision-proof.
        private const val SEP = "|~|"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): IaCookieJar = IaCookieJar(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: IaCookieJar): OkHttpClient {
        // Raise OkHttp's tiny default maxRequestsPerHost=5 so multi-connection downloads
        // work — but keep a SANE backstop. The DownloadManager already hard-caps total
        // connections to 24; these limits sit just above that so a runaway can't spawn
        // hundreds of sockets (which thermally crashed a handheld). Do NOT set these huge.
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 40
            maxRequestsPerHost = 32
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(24, 30, TimeUnit.SECONDS))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }
}
