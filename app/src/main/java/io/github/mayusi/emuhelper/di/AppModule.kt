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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Cookie jar with on-disk persistence so a logged-in session survives app
 * restarts (no re-login, no re-typing credentials).
 *
 * The configured source host routes downloads through per-request CDN nodes.
 * Three things must hold:
 *
 *  1. Cookies set for the root domain must be sent to EVERY subdomain host,
 *     not just the exact host that set them. (CDN nodes need the auth cookies.)
 *  2. The login flow sets a cookie, then immediately "deletes" it in the same
 *     response by re-setting the same name with an expiry in 1970. We must MERGE
 *     by name and DROP expired cookies, otherwise the `=deleted` sentinels
 *     (and stale values from earlier requests) clobber the real auth cookies and
 *     the source host treats us as logged-out -> 401 on restricted items.
 *  3. The session cookies (logged-in-user/logged-in-sig) are persistent (multi-year
 *     expiry). Persisting them to disk lets us restore the session on a cold
 *     start instead of doing a fresh network login every launch.
 *
 * Stored flat (not per-host) keyed by cookie name, because all session
 * cookies share the same registrable domain.
 */
class PersistentCookieJar(context: Context) : CookieJar {
    // name -> most recent non-expired cookie for the configured source domain
    private val store = ConcurrentHashMap<String, Cookie>()

    // Reactive login state so the UI can update the instant the (async, off-main)
    // disk restore finishes — otherwise an early synchronous isLoggedIn() reads the
    // still-empty store on cold start and the user looks signed-out until a manual
    // re-check. Starts false; flipped after loadFromDisk() and on every mutation.
    //
    // v0.5.4: refreshLoggedIn() is called on EVERY path that can change login status —
    // loadFromDisk (startup), saveFromResponse (a successful login stores the auth
    // cookie), loadForRequest (expired-cookie sweep) and clear(). Assigning to a
    // StateFlow.value is cheap and conflated: it only re-emits to collectors when the
    // boolean actually flips, so calling it redundantly is idempotent and safe. The key
    // guarantee is that a successful silent re-login (which goes through saveFromResponse)
    // immediately publishes loggedIn=true, so any live collector — e.g. the home screen's
    // combine — reacts the instant the session is restored, with no manual re-check.
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    // Recompute from the live store and publish. Idempotent + cheap: StateFlow conflates,
    // so this only emits when the computed value differs from the current one.
    private fun refreshLoggedIn() { _loggedIn.value = computeHasCookies() }

    // Touching EncryptedSharedPreferences does disk I/O and a KeyStore op, so we never
    // want it on the main thread. Both the initial load and every persist run on a
    // background thread / coroutine.
    private val appContext = context.applicationContext

    // FIX 5: do NOT memoize a FAILED open. A transient KeyStore hiccup right after boot
    // can make EncryptedSharedPreferences.create() throw once; if we cached that null via
    // `by lazy`, the user would be permanently logged out for the whole process lifetime.
    // Instead we cache only a SUCCESSFUL handle and retry create() on the next access.
    @Volatile private var cachedPrefs: SharedPreferences? = null
    private val prefsLock = Any()
    private val prefs: SharedPreferences?
        get() {
            cachedPrefs?.let { return it }
            synchronized(prefsLock) {
                cachedPrefs?.let { return it }
                val opened = openPrefs(appContext) // may return null on a transient failure
                if (opened != null) cachedPrefs = opened // cache success only; retry next time on null
                return opened
            }
        }

    /**
     * Returns an EncryptedSharedPreferences for cookie persistence, or null if secure
     * storage is unavailable. In the null case cookies are held only in memory for the
     * session and are never written to plaintext storage.
     */
    private fun openPrefs(ctx: Context): SharedPreferences? = try {
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
        Log.w("EmuHelper", "Secure cookie store unavailable; cookies will not persist this session", e)
        null // No plaintext fallback — cookies stay in-memory only.
    }

    // FIX 1: an awaitable signal that the disk restore has finished. Callers that need to
    // know the persisted-session state (HomeViewModel.init, the download gate) can suspend
    // on awaitRestored() instead of racing a blind timer. Completes exactly once, at the END
    // of the initial loadFromDisk()+refreshLoggedIn(); awaiting after completion returns
    // immediately. Safe to await from many places concurrently.
    private val restored = CompletableDeferred<Unit>()
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Restore the saved session off the main thread; the in-memory `store`
        // (a ConcurrentHashMap) fills in shortly after construction, and we then
        // publish the restored login state so the UI can react. We complete `restored`
        // at the very END so anyone awaiting it sees a fully-restored, correct loggedIn
        // value (no delay(400) race / no logged-out flash).
        restoreScope.launch {
            try {
                loadFromDisk()
                refreshLoggedIn()
            } finally {
                // complete() returns false if already completed — harmless; keeps this idempotent.
                restored.complete(Unit)
            }
        }
    }

    /**
     * Suspends until the initial on-disk cookie restore has completed (then [loggedIn] /
     * [hasCookies] reflect the persisted session). Returns immediately if the restore is
     * already done. Safe to call any number of times from any coroutine.
     */
    suspend fun awaitRestored() {
        restored.await()
    }

    /**
     * Force a re-evaluation of the login state from the live cookie store and re-publish it
     * to [loggedIn]. Normally unnecessary — every mutation path already calls the private
     * refreshLoggedIn() — but it gives callers (e.g. HomeViewModel after awaitRestored(), or a
     * user-initiated "refresh") a cheap, idempotent way to guarantee [loggedIn] reflects the
     * current store. Conflated: emits only if the value actually changed, so calling it when
     * already correct is a no-op for collectors.
     */
    fun publishLoginState() = refreshLoggedIn()

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
        // Defense in depth: only send cookies to archive.org and its CDN subdomains.
        // This ensures session cookies never travel to github.com or any other host,
        // even if a misconfigured client instance has the cookie jar attached.
        val isArchiveHost = host == "archive.org" || host.endsWith(".archive.org")
        if (!isArchiveHost) return emptyList()

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
            try { prefs?.edit()?.remove(KEY_COOKIES)?.apply() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    /**
     * True only when we hold the session cookies used by the configured source host
     * to prove a logged-in session, with a real (non-"deleted") value and not yet expired.
     */
    fun hasCookies(): Boolean = computeHasCookies()

    private fun computeHasCookies(): Boolean {
        val now = System.currentTimeMillis()
        // IMPORTANT: some hosts set an auth/CSRF token cookie even on a FAILED login,
        // so it must NOT be treated as proof of a session. The real session cookies
        // are `logged-in-sig` / `logged-in-user`, which appear ONLY after a successful
        // login. Require one of those.
        val authNames = listOf("logged-in-sig", "logged-in-user")
        return authNames.any { name ->
            val c = store[name]
            c != null && c.expiresAt > now && c.value.isNotBlank() && c.value != "deleted"
        }
    }

    // ---- persistence ------------------------------------------------------

    private fun persistToDisk() {
        try {
            val p = prefs ?: return // secure storage unavailable — skip persistence
            val now = System.currentTimeMillis()
            val serialized = store.values
                .filter { it.expiresAt > now && it.persistent } // only durable cookies worth restoring
                .map { serialize(it) }
                .toSet()
            p.edit().putStringSet(KEY_COOKIES, serialized).apply()
        } catch (e: Exception) {
            Log.w("EmuHelper", "Persisting cookies failed", e)
        }
    }

    private fun loadFromDisk() {
        try {
            val p = prefs ?: return // secure storage unavailable — nothing to load
            val now = System.currentTimeMillis()
            val saved = p.getStringSet(KEY_COOKIES, emptySet()) ?: emptySet()
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
        private const val KEY_COOKIES = "app_cookies_v1"
        // Delimiter that cannot occur in a cookie field (name/value/domain/path
        // use [A-Za-z0-9._/=-]); this triple token is collision-proof.
        private const val SEP = "|~|"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Shared [Json] instance for DataStore serialisation (GameListStore, HistoryStore).
     * Uses [ignoreUnknownKeys] so both stores can evolve their models without breaking
     * existing persisted data.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Provides
    @Singleton
    fun provideCookieJar(@ApplicationContext context: Context): PersistentCookieJar = PersistentCookieJar(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: PersistentCookieJar): OkHttpClient {
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
            // WARM-LANE REUSE (v3 laned engine): the durable resource is a small set of warm h2
            // connections, ~2 PINNED per mirror, reused for every 8 MB chunk on that lane. For
            // OkHttp to reuse the SAME warm connection across chunks, the idle connection must
            // survive the brief gap between one chunk finishing and the next ranged GET starting on
            // that lane. The old pool (24 idle, 30s keep-alive) evicted aggressively under churn and
            // the lane could pay a fresh TCP/TLS handshake + h2 slow-start on the next chunk. Widen
            // the keep-alive to 5 MINUTES so a pinned lane's connection stays warm for the whole
            // file; 8 max-idle is plenty (~2/host × up to 3 mirrors = ~6 warm sockets) without
            // hoarding. This is the change that lets the laned engine kill the per-chunk slow-start
            // tax (measured 8× median-vs-peak gap on the old per-chunk-host-repick engine).
            .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
            // Hygiene: state the protocol preference explicitly. IA serves HTTP/2 end-to-end and
            // OkHttp already negotiates h2 via ALPN, but pinning the list documents intent and keeps
            // h2 (multiplexed, low-handshake) first with http/1.1 as the graceful fallback.
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
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
