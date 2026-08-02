package et.alpha.mezgeb.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The parts of the lock that are arithmetic rather than storage.
 *
 * `AppLockRepository` needs `EncryptedSharedPreferences`, which needs the Android
 * Keystore, which Robolectric does not provide — so it is exercised on the device
 * instead, and said so in NOTES.md rather than faked here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockTest {

    // --- Hashing -----------------------------------------------------------------------

    @Test
    fun `the same PIN with the same salt verifies`() {
        val stored = PinCredential.hash("4821")

        assertTrue(PinCredential.matches("4821", stored))
    }

    @Test
    fun `a different PIN does not`() {
        val stored = PinCredential.hash("4821")

        assertFalse(PinCredential.matches("4822", stored))
        assertFalse(PinCredential.matches("482", stored))
        assertFalse(PinCredential.matches("", stored))
    }

    @Test
    fun `the same PIN hashes differently every time`() {
        // Random salt per PIN: without it, two shops with the PIN 1234 would store
        // identical hashes, and one leaked file would reveal the other.
        val first = PinCredential.hash("1234")
        val second = PinCredential.hash("1234")

        assertFalse(first.hashBase64 == second.hashBase64)
        assertFalse(first.saltBase64 == second.saltBase64)
        assertTrue(PinCredential.matches("1234", first))
        assertTrue(PinCredential.matches("1234", second))
    }

    @Test
    fun `the stored form contains nothing resembling the PIN`() {
        val stored = PinCredential.hash("482100")

        assertFalse(stored.hashBase64.contains("482100"))
        assertFalse(stored.saltBase64.contains("482100"))
    }

    @Test
    fun `a corrupted stored hash fails closed`() {
        // Never "unlock because the file looks odd".
        assertFalse(PinCredential.matches("1234", PinHash("not base64!!", "also not")))
    }

    @Test
    fun `only four to six digits are accepted`() {
        assertTrue(PinCredential.isAcceptable("1234"))
        assertTrue(PinCredential.isAcceptable("123456"))
        assertFalse(PinCredential.isAcceptable("123"))
        assertFalse(PinCredential.isAcceptable("1234567"))
        assertFalse(PinCredential.isAcceptable("12a4"))
        assertFalse(PinCredential.isAcceptable(""))
    }

    // --- Backoff ------------------------------------------------------------------------

    @Test
    fun `the first few mistakes cost nothing`() {
        // Four wrong entries is a fat thumb, not an attack.
        (0 until LockoutPolicy.FREE_ATTEMPTS).forEach { attempts ->
            assertEquals(0L, LockoutPolicy.backoffMillis(attempts))
        }
    }

    @Test
    fun `the delay doubles after that`() {
        assertEquals(30_000L, LockoutPolicy.backoffMillis(5))
        assertEquals(60_000L, LockoutPolicy.backoffMillis(6))
        assertEquals(120_000L, LockoutPolicy.backoffMillis(7))
        assertEquals(240_000L, LockoutPolicy.backoffMillis(8))
    }

    @Test
    fun `the delay is capped so the lock stays usable`() {
        // A lock that takes an hour to forgive is one a shop owner disables, and a
        // disabled lock protects nothing.
        val cap = LockoutPolicy.MAX_DELAY_SECONDS * 1000L

        assertEquals(cap, LockoutPolicy.backoffMillis(20))
        assertEquals(cap, LockoutPolicy.backoffMillis(200))
        assertTrue(LockoutPolicy.backoffMillis(1_000) > 0)
    }
}
