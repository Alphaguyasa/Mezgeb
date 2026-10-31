package et.alpha.mezgeb.domain.reconcile

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The balance chain. Every figure is in santim, and every break is asserted to the
 * santim: an approximate difference would send the shop looking for the wrong payment.
 */
class BalanceChainTest {

    private val minute = 60_000L

    private fun row(
        id: String,
        at: Long,
        amountMinor: Long,
        balanceAfterMinor: Long?,
        direction: Direction = Direction.IN,
        channel: Channel = Channel.TELEBIRR,
        possibleDuplicateOfId: String? = null,
        isDeleted: Boolean = false,
    ) = Transaction(
        id = id,
        rawMessageId = null,
        direction = direction,
        amountMinor = amountMinor,
        currency = "ETB",
        channel = channel,
        counterpartyName = null,
        counterpartyPhone = null,
        providerTxnId = null,
        balanceAfterMinor = balanceAfterMinor,
        occurredAt = at,
        categoryId = null,
        note = null,
        isReconciled = false,
        reconciliationSessionId = null,
        isDeleted = isDeleted,
        editedAt = null,
        possibleDuplicateOfId = possibleDuplicateOfId,
    )

    @Test
    fun `a continuous chain gives nothing`() {
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 110_000),
            row("b", 2 * minute, 25_050, balanceAfterMinor = 135_050),
            row("c", 3 * minute, 5_050, balanceAfterMinor = 130_000, direction = Direction.OUT),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `a chain with a gap gives one break with the exact difference`() {
        // 300.75 birr arrived between b and c and was never captured.
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 110_000),
            row("b", 2 * minute, 20_000, balanceAfterMinor = 130_000),
            row("c", 9 * minute, 5_000, balanceAfterMinor = 165_075),
            row("d", 10 * minute, 1_000, balanceAfterMinor = 166_075),
        )

        assertEquals(
            listOf(BalanceBreak(Channel.TELEBIRR, afterAt = 2 * minute, beforeAt = 9 * minute, differenceMinor = 30_075)),
            BalanceChain.findBreaks(rows),
        )
    }

    @Test
    fun `input order does not matter`() {
        val rows = listOf(
            row("c", 3 * minute, 5_000, balanceAfterMinor = 115_000),
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 10_000, balanceAfterMinor = 110_000),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `two payments in the same minute are put in the order the balances follow`() {
        // Ids sort the wrong way round on purpose.
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("z", 2 * minute, 1_000, balanceAfterMinor = 101_000),
            row("y", 2 * minute, 2_000, balanceAfterMinor = 103_000),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `a row without a balance still moves the prediction`() {
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 4_000, balanceAfterMinor = null),
            row("c", 3 * minute, 1_000, balanceAfterMinor = 105_000),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `a small drop after an outgoing transfer is a fee, not a break`() {
        // 500 birr sent, the balance fell by 505.75: 5.75 birr of fee.
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 50_000, balanceAfterMinor = 49_425, direction = Direction.OUT),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `a drop bigger than any fee is a break`() {
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 50_000, balanceAfterMinor = 30_000, direction = Direction.OUT),
        )

        assertEquals(-20_000L, BalanceChain.findBreaks(rows).single().differenceMinor)
    }

    @Test
    fun `a rise is never excused as a fee`() {
        // One birr more than predicted after an outgoing transfer: money arrived.
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 50_000, balanceAfterMinor = 50_100, direction = Direction.OUT),
        )

        assertEquals(100L, BalanceChain.findBreaks(rows).single().differenceMinor)
    }

    @Test
    fun `there is no fee allowance without an outgoing transaction`() {
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 10_000, balanceAfterMinor = 109_900),
        )

        assertEquals(-100L, BalanceChain.findBreaks(rows).single().differenceMinor)
    }

    @Test
    fun `wallets are chained separately`() {
        val rows = listOf(
            row("t1", 1 * minute, 10_000, balanceAfterMinor = 100_000, channel = Channel.TELEBIRR),
            row("c1", 2 * minute, 10_000, balanceAfterMinor = 500_000, channel = Channel.CBE_BIRR),
            row("t2", 3 * minute, 10_000, balanceAfterMinor = 110_000, channel = Channel.TELEBIRR),
            row("c2", 4 * minute, 10_000, balanceAfterMinor = 515_000, channel = Channel.CBE_BIRR),
        )

        assertEquals(
            listOf(BalanceBreak(Channel.CBE_BIRR, afterAt = 2 * minute, beforeAt = 4 * minute, differenceMinor = 5_000)),
            BalanceChain.findBreaks(rows),
        )
    }

    @Test
    fun `bank rows are not chained`() {
        // Several accounts share the BANK channel; their balances do not form one chain.
        val rows = listOf(
            row("b1", 1 * minute, 10_000, balanceAfterMinor = 100_000, channel = Channel.BANK),
            row("b2", 2 * minute, 10_000, balanceAfterMinor = 900_000, channel = Channel.BANK),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `a deleted row is left out of the chain`() {
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("gone", 2 * minute, 7_000, balanceAfterMinor = null, isDeleted = true),
            row("b", 3 * minute, 10_000, balanceAfterMinor = 110_000),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `a possible duplicate restarts the chain instead of reporting a break`() {
        // "b2" is flagged as a copy of "b" and states the same balance. Counting it would
        // report a 100 birr drop; that question belongs to the duplicate explanation.
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 10_000, balanceAfterMinor = 110_000),
            row("b2", 3 * minute, 10_000, balanceAfterMinor = 110_000, possibleDuplicateOfId = "b"),
            row("c", 4 * minute, 5_000, balanceAfterMinor = 115_000),
        )

        assertTrue(BalanceChain.findBreaks(rows).isEmpty())
    }

    @Test
    fun `the chain picks up again after a break`() {
        val rows = listOf(
            row("a", 1 * minute, 10_000, balanceAfterMinor = 100_000),
            row("b", 2 * minute, 10_000, balanceAfterMinor = 120_000),
            row("c", 3 * minute, 10_000, balanceAfterMinor = 130_000),
        )

        val breaks = BalanceChain.findBreaks(rows)

        assertEquals(1, breaks.size)
        assertEquals(10_000L, breaks.single().differenceMinor)
    }
}
