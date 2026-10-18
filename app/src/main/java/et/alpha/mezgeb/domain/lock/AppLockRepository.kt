package et.alpha.mezgeb.domain.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of offering a PIN. */
sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data class Wrong(val failedAttempts: Int) : UnlockResult
    /** Too many wrong guesses. [retryAtMillis] is when another will be looked at. */
    data class LockedOut(val retryAtMillis: Long) : UnlockResult
}

/**
 * The PIN, where it lives, and how often it may be guessed at.
 *
 * `EncryptedSharedPreferences` rather than DataStore: the file holds a hash of a
 * four-digit secret, and the Keystore-backed key is what stops that file being
 * brute-forced somewhere else. DataStore has no equivalent, and rolling one would
 * mean writing key management by hand.
 *
 * Hashing runs on IO, not because the file is slow but because PBKDF2 is deliberately
 * expensive — sixty thousand iterations on the main thread is a visibly janky unlock.
 */
@Singleton
class AppLockRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * The `MasterKeys` form, not `MasterKey.Builder`.
     *
     * The builder arrived in security-crypto 1.1.0, which has been in alpha for years.
     * This project already chose stable biometric over its alpha line for the same
     * reason: an alpha dependency holding the key to a shop's lock is a poor trade for
     * a nicer constructor.
     */
    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            FILE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun isLockEnabled(): Boolean = prefs.contains(KEY_HASH)

    /** Seconds of background before the app asks again. */
    fun timeoutSeconds(): Int = prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS)

    suspend fun setTimeoutSeconds(seconds: Int) = withContext(io) {
        prefs.edit { putInt(KEY_TIMEOUT, seconds.coerceAtLeast(0)) }
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC, false)

    suspend fun setBiometricEnabled(enabled: Boolean) = withContext(io) {
        prefs.edit { putBoolean(KEY_BIOMETRIC, enabled) }
    }

    suspend fun setPin(pin: String): Boolean = withContext(io) {
        if (!PinCredential.isAcceptable(pin)) return@withContext false
        val hashed = PinCredential.hash(pin)
        prefs.edit {
            putString(KEY_SALT, hashed.saltBase64)
            putString(KEY_HASH, hashed.hashBase64)
            putInt(KEY_FAILED, 0)
        }
        true
    }

    /**
     * Removing the lock also removes the record of failed attempts, so a shop that
     * turns the lock off and on again is not still serving a lockout.
     */
    suspend fun clearPin() = withContext(io) {
        prefs.edit {
            remove(KEY_SALT)
            remove(KEY_HASH)
            remove(KEY_BIOMETRIC)
            putInt(KEY_FAILED, 0)
        }
    }

    suspend fun verify(pin: String): UnlockResult = withContext(io) {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val now = clock.millis()
        if (now < lockedUntil) return@withContext UnlockResult.LockedOut(lockedUntil)

        val salt = prefs.getString(KEY_SALT, null)
        val hash = prefs.getString(KEY_HASH, null)
        if (salt == null || hash == null) return@withContext UnlockResult.Unlocked

        if (PinCredential.matches(pin, PinHash(salt, hash))) {
            prefs.edit {
                putInt(KEY_FAILED, 0)
                putLong(KEY_LOCKED_UNTIL, 0L)
            }
            return@withContext UnlockResult.Unlocked
        }

        val failed = prefs.getInt(KEY_FAILED, 0) + 1
        val backoff = LockoutPolicy.backoffMillis(failed)
        prefs.edit {
            putInt(KEY_FAILED, failed)
            putLong(KEY_LOCKED_UNTIL, if (backoff > 0) now + backoff else 0L)
        }

        if (backoff > 0) UnlockResult.LockedOut(now + backoff) else UnlockResult.Wrong(failed)
    }

    /** How long the app is still refusing to look, or zero. */
    fun lockoutRemainingMillis(): Long =
        (prefs.getLong(KEY_LOCKED_UNTIL, 0L) - clock.millis()).coerceAtLeast(0L)

    private companion object {
        const val FILE_NAME = "mezgeb_lock"
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILED = "failed_attempts"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_TIMEOUT = "timeout_seconds"
        const val KEY_BIOMETRIC = "biometric_enabled"

        /** Per the session brief. Long enough to answer the phone, short enough to matter. */
        const val DEFAULT_TIMEOUT_SECONDS = 60
    }
}
