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
    private val uptime: DeviceUptime,
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
        clearLockout()
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
        clearLockout()
    }

    /**
     * The lockout is timed on [DeviceUptime], not the wall clock, so setting the
     * phone's time forward does not end it early. `retryAtMillis` is still a wall-clock
     * time because the lock screen counts down from it straight away; nothing here
     * decides anything with it.
     */
    suspend fun verify(pin: String): UnlockResult = withContext(io) {
        val remaining = lockoutRemainingMillis()
        if (remaining > 0) return@withContext UnlockResult.LockedOut(clock.millis() + remaining)

        val salt = prefs.getString(KEY_SALT, null)
        val hash = prefs.getString(KEY_HASH, null)
        if (salt == null || hash == null) return@withContext UnlockResult.Unlocked

        if (PinCredential.matches(pin, PinHash(salt, hash))) {
            prefs.edit { putInt(KEY_FAILED, 0) }
            clearLockout()
            return@withContext UnlockResult.Unlocked
        }

        val failed = prefs.getInt(KEY_FAILED, 0) + 1
        val backoff = LockoutPolicy.backoffMillis(failed)
        prefs.edit { putInt(KEY_FAILED, failed) }
        if (backoff > 0) {
            storeLockout(Lockout(uptime.bootCount(), uptime.elapsedMillis(), backoff))
            UnlockResult.LockedOut(clock.millis() + backoff)
        } else {
            UnlockResult.Wrong(failed)
        }
    }

    /** How long the app is still refusing to look, or zero. */
    fun lockoutRemainingMillis(): Long {
        val stored = storedLockout() ?: return 0L
        val elapsedNow = uptime.elapsedMillis()
        val lockout = LockoutTimer.anchored(stored, uptime.bootCount(), elapsedNow)
        val remaining = LockoutTimer.remainingMillis(lockout, elapsedNow)
        when {
            // Served in full: forget it, so a restart later does not start it again.
            remaining == 0L -> clearLockout()
            // Re-anchored after a restart: keep the new start, or every read after
            // this one would start the wait over again.
            lockout != stored -> storeLockout(lockout)
        }
        return remaining
    }

    private fun storedLockout(): Lockout? {
        val duration = prefs.getLong(KEY_LOCK_DURATION, 0L)
        if (duration > 0L) {
            return Lockout(
                bootCount = prefs.getInt(KEY_LOCK_BOOT, -1),
                startedAtElapsed = prefs.getLong(KEY_LOCK_STARTED, Long.MAX_VALUE),
                durationMillis = duration,
            )
        }
        // A lockout written before it was timed on uptime: only its wall-clock end is
        // known. What is left of it is served from now, capped at the longest delay,
        // and never less than nothing; it is rewritten in the new form on first read.
        val legacyUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val left = (legacyUntil - clock.millis())
            .coerceIn(0L, LockoutPolicy.MAX_DELAY_SECONDS * 1000L)
        if (left == 0L) return null
        return Lockout(uptime.bootCount(), uptime.elapsedMillis(), left).also(::storeLockout)
    }

    private fun storeLockout(lockout: Lockout) = prefs.edit {
        putInt(KEY_LOCK_BOOT, lockout.bootCount)
        putLong(KEY_LOCK_STARTED, lockout.startedAtElapsed)
        putLong(KEY_LOCK_DURATION, lockout.durationMillis)
        remove(KEY_LOCKED_UNTIL)
    }

    private fun clearLockout() = prefs.edit {
        remove(KEY_LOCK_BOOT)
        remove(KEY_LOCK_STARTED)
        remove(KEY_LOCK_DURATION)
        remove(KEY_LOCKED_UNTIL)
    }

    private companion object {
        const val FILE_NAME = "mezgeb_lock"
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILED = "failed_attempts"
        /** The wall-clock lockout end written by earlier versions. Read once, then removed. */
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_LOCK_BOOT = "lockout_boot_count"
        const val KEY_LOCK_STARTED = "lockout_started_elapsed"
        const val KEY_LOCK_DURATION = "lockout_duration"
        const val KEY_TIMEOUT = "timeout_seconds"
        const val KEY_BIOMETRIC = "biometric_enabled"

        /** Per the session brief. Long enough to answer the phone, short enough to matter. */
        const val DEFAULT_TIMEOUT_SECONDS = 60
    }
}
