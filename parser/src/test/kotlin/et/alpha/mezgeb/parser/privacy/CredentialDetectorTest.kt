package et.alpha.mezgeb.parser.privacy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both directions matter, and they pull against each other.
 *
 * The first group is what a real device produced: a bank reset a mobile-banking PIN
 * and sent it in cleartext, and the app stored it. The second group is the reason
 * this cannot simply match on the word "PIN" — payment messages carry
 * "never share your PIN" footers, and dropping one of those costs a shop a payment.
 */
class CredentialDetectorTest {

    @Test
    fun `a stated PIN is a credential`() {
        // The shape that was actually found on a phone, reworded.
        assertTrue(
            CredentialDetector.containsCredential(
                "Dear Customer,\n\nYour Hibir Mobile TPIN has been reset successfully \n\n" +
                    "New TPIN is 0000\n\nHibir Mobile",
            ),
        )
    }

    @Test
    fun `the usual one-time code shapes are credentials`() {
        listOf(
            "Your OTP is 483920",
            "Your OTP: 483920",
            "Verification code: 483920",
            "Your one-time password is A1B2C3",
            "483920 is your verification code",
            "Use code 483920 to confirm your transfer",
            "Your new password = temp1234",
            "Your access code is 9911",
        ).forEach {
            assertTrue("should have been treated as a credential: $it") {
                CredentialDetector.containsCredential(it)
            }
        }
    }

    @Test
    fun `an amharic credential message is caught`() {
        assertTrue(CredentialDetector.containsCredential("የይለፍ ቃልዎ 4768 ነው"))
        assertTrue(CredentialDetector.containsCredential("ሚስጥራዊ ቁጥር: 483920"))
    }

    // --- The other direction, which is the expensive one to get wrong ----------------

    @Test
    fun `a warning not to share a PIN is not a credential`() {
        // CBE sends these, and they carry no secret. Dropping a message like this
        // costs nothing; the same mistake on a payment message costs a transaction.
        listOf(
            "Dear customer, beware of fraud. Never share your PIN with anyone.",
            "CBE will never ask for your PIN or password.",
            "Do not share your OTP with any caller.",
        ).forEach {
            assertFalse("should not have been treated as a credential: $it") {
                CredentialDetector.containsCredential(it)
            }
        }
    }

    @Test
    fun `a payment message with a security footer is still a payment message`() {
        // The case that would silently cost a shop money.
        val body = "Dear Customer your Account 1********0000 has been credited with ETB " +
            "450.00. Your Current Balance is ETB 1,200.00. Never share your PIN."

        assertFalse(CredentialDetector.containsCredential(body))
    }

    @Test
    fun `ordinary payment messages are untouched`() {
        listOf(
            "You have received ETB 450.00 from ABEBE. Your transaction number is CJ4T5X9AB1.",
            "You have successfully transferred ETB200 Charges: ETB0.80 with VAT: ETB0.12",
            "Dear Customer, A debit transaction of ETB 2000.0. has occurred on your account",
        ).forEach {
            assertFalse("payment message wrongly flagged: $it") {
                CredentialDetector.containsCredential(it)
            }
        }
    }

    @Test
    fun `a reference number is not a credential just because it follows a colon`() {
        assertFalse(
            CredentialDetector.containsCredential(
                "EBIRR-KAAFI-> Id: 000000000008, You sent ETB1,000 to NAME_59",
            ),
        )
    }

    @Test
    fun `an empty message is not a credential`() {
        assertFalse(CredentialDetector.containsCredential(""))
    }
}
