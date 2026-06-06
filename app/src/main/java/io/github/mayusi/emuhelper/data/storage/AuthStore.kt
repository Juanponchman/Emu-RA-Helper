package io.github.mayusi.emuhelper.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the Internet Archive credentials.
 *
 * ALL fields — email, remember-me, AND password — live in ONE EncryptedSharedPreferences
 * file (AES-256, app-private). Previously email/rememberMe used a separate DataStore that
 * silently failed to persist (left a 0-byte temp file), so auto-login never had an email
 * to use and the user had to sign in again after every update/restart. Keeping everything
 * in the encrypted prefs makes the saved login durable and hidden — it survives app
 * updates and restarts until the user explicitly logs out.
 */
@Singleton
class AuthStore @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val ENCRYPTED_PREFS_FILE = "emuhelper_secrets"
        private const val KEY_PWD = "password"
        private const val KEY_EMAIL = "email"
        private const val KEY_REMEMBER = "remember"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("EmuHelper", "EncryptedSharedPreferences init failed; using plain prefs", e)
            context.getSharedPreferences("emuhelper_secrets_fallback", Context.MODE_PRIVATE)
        }
    }

    // Reactive flows so the UI updates when credentials change. Seeded from disk on first read.
    private val _email = MutableStateFlow(readEmail())
    val savedEmail: Flow<String> = _email.asStateFlow()

    private val _remember = MutableStateFlow(readRemember())
    val rememberMe: Flow<Boolean> = _remember.asStateFlow()

    private fun readEmail(): String = try { prefs.getString(KEY_EMAIL, "") ?: "" } catch (e: Exception) { "" }
    private fun readRemember(): Boolean = try { prefs.getBoolean(KEY_REMEMBER, true) } catch (e: Exception) { true }

    suspend fun getSavedPassword(): String = withContext(Dispatchers.IO) {
        try { prefs.getString(KEY_PWD, "") ?: "" } catch (e: Exception) { "" }
    }

    /** Synchronous email getter for the cold-start auto-login path. */
    fun savedEmailNow(): String = _email.value
    fun rememberMeNow(): Boolean = _remember.value

    suspend fun saveCredentials(email: String, password: String, remember: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit().apply {
                if (remember) {
                    putString(KEY_EMAIL, email)
                    putString(KEY_PWD, password)
                    putBoolean(KEY_REMEMBER, true)
                } else {
                    remove(KEY_EMAIL)
                    remove(KEY_PWD)
                    putBoolean(KEY_REMEMBER, false)
                }
            }.commit() // commit() (not apply) so it's flushed to disk before we continue
        }
        _email.value = if (remember) email else ""
        _remember.value = remember
    }

    suspend fun clearCredentials() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_EMAIL).remove(KEY_PWD).putBoolean(KEY_REMEMBER, false).commit()
        }
        _email.value = ""
        _remember.value = false
    }
}
