package et.alpha.mezgeb.domain.reconcile

import et.alpha.mezgeb.domain.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weekly card's arithmetic, from the reconcile explanations it is built on. */
class MissedMoneyTest {

    @Test
    fun `balance jumps and unread amounts add up to what may be missing`() {
        val missed = MissedMoney.from(
            listOf(
                GapExplanation.UnrecordedBalanceChange(
                    channel = Channel.TELEBIRR,
                    afterAt = 1L,
                    beforeAt = 2L,
                    differenceMinor = 30_000,
                ),
                // A drop the ledger cannot explain is money at stake too, not a credit.
                GapExplanation.UnrecordedBalanceChange(
                    channel = Channel.CBE_BIRR,
                    afterAt = 3L,
                    beforeAt = 4L,
                    differenceMinor = -5_000,
                ),
                GapExplanation.UnmatchedMessages(count = 3, partialTotalMinor = 12_000, amountsFound = 2),
            ),
        )

        assertEquals(2, missed.balanceJumpCount)
        assertEquals(35_000L, missed.balanceJumpMinor)
        assertEquals(3, missed.unreadMessageCount)
        assertEquals(2, missed.unreadAmountsFound)
        assertEquals(47_000L, missed.possiblyMissingMinor)
        assertFalse(missed.isEmpty)
    }

    @Test
    fun `a possible double count is never added to the missing money`() {
        val missed = MissedMoney.from(
            listOf(GapExplanation.PossibleDuplicates(count = 2, totalMinor = 90_000)),
        )

        assertEquals(0L, missed.possiblyMissingMinor)
        assertEquals(2, missed.possibleDuplicateCount)
        assertEquals(90_000L, missed.possibleDuplicatesMinor)
        assertFalse(missed.isEmpty)
    }

    @Test
    fun `unread messages with no readable amount are counted, at zero birr`() {
        val missed = MissedMoney.from(
            listOf(GapExplanation.UnmatchedMessages(count = 4, partialTotalMinor = null, amountsFound = 0)),
        )

        assertEquals(4, missed.unreadMessageCount)
        assertEquals(0L, missed.possiblyMissingMinor)
        assertFalse(missed.isEmpty)
    }

    @Test
    fun `recorded money that is only unsorted or unpaid is not missed money`() {
        val missed = MissedMoney.from(
            listOf(
                GapExplanation.UncategorizedTransactions(count = 5, totalMinor = 100_000),
                GapExplanation.OutstandingCredit(customerCount = 2, totalMinor = 40_000),
                GapExplanation.EditedSincePeriodOpened(count = 1),
            ),
        )

        assertTrue(missed.isEmpty)
        assertEquals(MissedMoney(), missed)
    }
}
