package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.domain.model.ParseState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cleaning up after an earlier build.
 *
 * A PIN sitting in a ledger does not become harmless because the next version would
 * not have kept it, so this runs on every launch rather than only on first install.
 */
class PrivacyPurgeTest : DatabaseTest() {

    private fun purge() = PrivacyPurge(db.rawMessageDao())

    @Test
    fun `a stored PIN is removed`() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(
                id = "pin",
                sender = "HIBRETBANK",
                body = "Your Hibir Mobile TPIN has been reset successfully. New TPIN is 0000",
                contentHash = "pin",
                parseState = ParseState.UNMATCHED,
            ),
        )

        assertEquals(1, purge().run().total)
        assertNull(db.rawMessageDao().getById("pin"))
    }

    @Test
    fun `payment messages are left alone`() = runTest {
        db.rawMessageDao().insertAllIgnoringDuplicates(
            listOf(
                rawMessage(
                    id = "payment",
                    sender = "CBE",
                    body = "your Account 1*****0000 has been credited with ETB 450.00. " +
                        "Never share your PIN.",
                    contentHash = "payment",
                ),
                rawMessage(
                    id = "fraud_notice",
                    sender = "CBE",
                    body = "Dear customer, beware of fraud. Never share your PIN with anyone.",
                    contentHash = "fraud",
                    parseState = ParseState.IGNORED,
                ),
            ),
        )

        assertEquals(0, purge().run().total)
        assertNotNull(db.rawMessageDao().getById("payment"))
        assertNotNull(db.rawMessageDao().getById("fraud_notice"))
    }

    @Test
    fun `the payment survives even when its message has to go`() = runTest {
        // If a credential-bearing message ever did parse into a transaction, the money
        // record is what the shop needs; the text is the part that must not be kept.
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(
                id = "raw",
                body = "Transfer done. Your new access code is 9911",
                contentHash = "raw",
            ),
        )
        db.transactionDao().insert(transaction("txn", rawMessageId = "raw", occurredAt = T0))

        assertEquals(1, purge().run().total)

        val survivor = db.transactionDao().getById("txn")
        assertNotNull("the transaction must survive", survivor)
        // SET NULL on the foreign key: the row keeps its money and loses the text.
        assertNull(survivor?.rawMessageId)
    }

    @Test
    fun `running twice changes nothing the second time`() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(id = "pin", body = "Your OTP is 483920", contentHash = "pin"),
        )

        assertEquals(1, purge().run().total)
        assertEquals(0, purge().run().total)
    }

    @Test
    fun `a private message an earlier build stored is removed`() = runTest {
        // What a single plain SMS on a real phone left behind: the notification
        // listener stored it and marked it IGNORED, which kept the text.
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(
                id = "personal",
                sender = "+251911000009",
                body = "see you at the shop tomorrow",
                contentHash = "personal",
                parseState = ParseState.IGNORED,
            ),
        )

        val summary = purge().run()

        assertEquals(1, summary.nonFinancial)
        assertEquals(0, summary.credentials)
        assertNull(db.rawMessageDao().getById("personal"))
    }

    @Test
    fun `noise from a financial sender is kept`() = runTest {
        // Removing these would empty the review queue of the very messages that prove
        // an allowlisted sender is sending something the parser cannot read.
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(
                id = "cbe_notice",
                sender = "CBE",
                body = "Dear customer, beware of fraud.",
                contentHash = "cbe_notice",
                parseState = ParseState.IGNORED,
            ),
        )

        assertEquals(0, purge().run().total)
        assertNotNull(db.rawMessageDao().getById("cbe_notice"))
    }

    @Test
    fun `a clean database is left untouched`() = runTest {
        // Senders matter now, and the fixture default of "telebirr" is the
        // *marketing* sender the corpus found — transactions arrive from 127.
        db.rawMessageDao().insertAllIgnoringDuplicates(
            (1..5).map { rawMessage(id = "m$it", sender = "CBE", contentHash = "m$it") },
        )

        assertEquals(0, purge().run().total)
        assertEquals(5, db.rawMessageDao().count())
    }

    @Test
    fun `the scan gets through more messages than one page`() = runTest {
        // The paging cursor has to account for rows disappearing under it.
        db.rawMessageDao().insertAllIgnoringDuplicates(
            (1..250).map { index ->
                rawMessage(
                    id = "m$index",
                    sender = "CBE",
                    body = if (index % 50 == 0) "Your OTP is 48392$index" else "no secrets here",
                    contentHash = "m$index",
                    receivedAt = T0 + index,
                )
            },
        )

        assertEquals(5, purge().run().total)
        assertEquals(245, db.rawMessageDao().count())
    }
}
