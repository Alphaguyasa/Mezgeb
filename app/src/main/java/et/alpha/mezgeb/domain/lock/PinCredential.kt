package et.alpha.mezgeb.domain.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A stored PIN, as a hash and the salt it was made with. Never the PIN. */
data class PinHash(val saltBase64: String, val hashBase64: String)

/**
 * Hashing and checking the app-lock PIN.
 *
 * ### What this protects against, and what it does not
 *
 * A four-digit PIN has ten thousand possibilities. No hash function makes that
 * unguessable to someone who can run the comparison themselves, however many
 * iterations it uses — PBKDF2 buys time per guess, not secrecy.
 *
 * So the real barrier is where the hash lives: `EncryptedSharedPreferences`, behind a
 * key the Android Keystore will not export. An attacker with the file and without the
 * device gets ciphertext. An attacker with an unlocked, rooted device gets in
 * regardless, and no amount of key stretching changes that.
 *
 * The iteration count is still worth having — it makes an on-device brute force take
 * minutes rather than milliseconds, and it is the difference between a snatched phone
 * being opened in a queue and being given up on. It is deliberately lower than the
 * backup's, because this one runs on every unlock and a lock screen that takes a
 * second to respond is a lock screen people turn off.
 */
object PinCredential {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** Tuned for an unlock, not for a passphrase. See the class note. */
    const val ITERATIONS = 60_000

    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun isAcceptable(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    fun hash(pin: String, random: SecureRandom = SecureRandom()): PinHash {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinHash(
            saltBase64 = encode(salt),
            hashBase64 = encode(derive(pin, salt)),
        )
    }

    /**
     * Constant-time comparison.
     *
     * A `==` on the hashes leaks how many leading bytes matched through timing, which
     * against a four-digit space is a real shortcut rather than a theoretical one.
     */
    fun matches(pin: String, stored: PinHash): Boolean {
        val salt = runCatching { decode(stored.saltBase64) }.getOrNull() ?: return false
        val expected = runCatching { decode(stored.hashBase64) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt), expected)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val derived = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        spec.clearPassword()
        return derived
    }

    private fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
}

/**
 * How long the app refuses to look at another PIN.
 *
 * Exponential after five failures, because the first four are a fat thumb and the
 * twentieth is somebody trying every combination. Capped, because a lock that takes
 * an hour to forgive is one a shop owner will disable rather than wait out — and a
 * disabled lock protects nothing.
 */
object LockoutPolicy {

    const val FREE_ATTEMPTS = 5
    const val BASE_DELAY_SECONDS = 30L
    const val MAX_DELAY_SECONDS = 15 * 60L

    /** Milliseconds to wait after [failedAttempts] consecutive failures. Zero is free. */
    fun backoffMillis(failedAttempts: Int): Long {
        if (failedAttempts < FREE_ATTEMPTS) return 0L
        val doublings = (failedAttempts - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
        val seconds = (BASE_DELAY_SECONDS shl doublings).coerceAtMost(MAX_DELAY_SECONDS)
        return seconds * 1000L
    }

    /** Enough doublings to reach the cap; more would only risk overflowing the shift. */
    private const val MAX_DOUBLINGS = 5
}
