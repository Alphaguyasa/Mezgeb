package et.alpha.mezgeb.parser.corpus

import et.alpha.mezgeb.parser.model.ParseOutcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-written malformed and unexpected messages, run against the bundled rules.
 *
 * The corpus holds messages as providers actually send them. These are the ones they
 * send on a bad day: cut off, garbled, in another currency, or in a template nobody has
 * taught the app yet. Every one must be skipped and flagged — left for the review
 * queue — rather than read as a payment. Built from the anonymized corpus templates;
 * no real names, numbers or balances.
 */
class FailSafeFixturesTest {

    private val parser = CorpusAccuracy.parser()

    private fun parse(sender: String, body: String) =
        parser.parse(sender, body, CorpusAccuracy.RECEIVED_AT)

    private fun assertNotRead(sender: String, body: String) {
        val outcome = parse(sender, body)
        assertTrue(
            outcome !is ParseOutcome.Success && outcome !is ParseOutcome.Ambiguous,
            "expected the message to be skipped and flagged, got $outcome",
        )
    }

    // --- The template these variations are built from, read correctly first ----------

    @Test
    fun `the unmodified template is read`() {
        val success = assertIs<ParseOutcome.Success>(parse(TELEBIRR, TELEBIRR_RECEIVED))
        assertEquals(200_000L, success.fields.amountMinor)
        assertEquals("YYN1VGGY2D", success.fields.providerTxnId)
        assertEquals(200_170L, success.fields.balanceAfterMinor)
    }

    // --- Truncated -----------------------------------------------------------------------

    @Test
    fun `a preview cut inside the amount is not read`() {
        assertNotRead(TELEBIRR, "Dear NAME_128, You have received ETB 2,0…")
    }

    @Test
    fun `a preview cut inside the balance drops the balance, not the payment`() {
        val cut = TELEBIRR_RECEIVED.substringBefore("2,001.70") + "2,0…"
        val success = assertIs<ParseOutcome.Success>(parse(TELEBIRR, cut))

        assertEquals(200_000L, success.fields.amountMinor)
        assertNull(success.fields.balanceAfterMinor, "a cut-off balance must not be guessed")
    }

    @Test
    fun `a balance cut after its decimal point is not read as a whole number`() {
        val cut = CBE_DEBIT.substringBefore("ETB22.92") + "ETB22."
        val outcome = parse(CBE, cut)

        if (outcome is ParseOutcome.Success) assertNull(outcome.fields.balanceAfterMinor)
    }

    // --- Malformed -----------------------------------------------------------------------

    @Test
    fun `a garbled amount is not read`() {
        assertNotRead(TELEBIRR, TELEBIRR_RECEIVED.replace("ETB 2,000.00", "ETB 2,O00.00"))
        assertNotRead(TELEBIRR, TELEBIRR_RECEIVED.replace("ETB 2,000.00", "ETB ,."))
    }

    @Test
    fun `a message with the amount missing is not read`() {
        assertNotRead(TELEBIRR, TELEBIRR_RECEIVED.replace("ETB 2,000.00 ", ""))
    }

    @Test
    fun `an empty or blank body is not read`() {
        assertNotRead(TELEBIRR, "")
        assertNotRead(CBE, "   ")
    }

    // --- Unexpected formats --------------------------------------------------------------

    @Test
    fun `an amount in another currency is not read as birr`() {
        assertNotRead(TELEBIRR, TELEBIRR_RECEIVED.replace("ETB 2,000.00", "USD 2,000.00"))
    }

    @Test
    fun `an unknown template from a known sender is not read`() {
        assertNotRead(
            TELEBIRR,
            "Dear NAME_128, your telebirr account will be upgraded tonight. Your current " +
                "balance is ETB 2,001.70. Thank you for using telebirr Ethio telecom",
        )
    }

    @Test
    fun `a known template from the wrong sender is not read`() {
        assertNotRead(EBIRR, TELEBIRR_RECEIVED)
    }

    private companion object {
        const val TELEBIRR = "127"
        const val CBE = "CBE"
        const val EBIRR = "EBIRR"

        /** corpus/train/telebirr.json, telebirr_003. */
        const val TELEBIRR_RECEIVED =
            "Dear NAME_128, You have received ETB 2,000.00 by transaction number YYN1VGGY2D " +
                "on 2026-07-30 16:49:24 from Commercial Bank of Ethiopia to your telebirr " +
                "Account 000000000000 - NAME_14. Your current balance is ETB 2,001.70. " +
                "Thank you for using telebirr Ethio telecom"

        /** corpus/train/cbe.json, the first debit sample, without its links. */
        const val CBE_DEBIT =
            "Dear NAME_36 A debit transaction of ETB 2000.0. has occurred on your account " +
                "1********0001. Service charge of ETB 10.00 and VAT(15%) of ETB1.50 and " +
                "Disaster Recovery(5%) of 0.50 with total of ETB2012.00 .Your current " +
                "balance is ETB22.92. Thanks for Banking with CBE."
    }
}
