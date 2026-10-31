package et.alpha.mezgeb.domain.reconcile

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction

/**
 * A place where a wallet's balance moved by more than the recorded transactions explain.
 *
 * @param afterAt when the balance was last consistent with the ledger.
 * @param beforeAt the transaction whose stated balance did not follow.
 * @param differenceMinor stated balance minus the balance the ledger predicts. Positive
 *   means money arrived that was never recorded; negative means money left.
 */
data class BalanceBreak(
    val channel: Channel,
    val afterAt: Long,
    val beforeAt: Long,
    val differenceMinor: Long,
)

/**
 * Walks each wallet's `balanceAfterMinor` chain and reports where it breaks.
 *
 * Every telebirr and CBE Birr message states the balance after it. The previous balance
 * plus or minus the next amount should equal the next balance. When it does not, a
 * message was never captured: the listener was killed, a notification was swiped away
 * before it was read, or the phone was off. This is the one check that can see a
 * payment the app never heard about.
 *
 * It only reports. A break is never "corrected" by inventing a transaction, because the
 * difference can be several payments, a reversal or a fee, and only the shop can say.
 *
 * ### Which channels
 *
 * Only [Channel.TELEBIRR] and [Channel.CBE_BIRR]: each is one wallet per phone. [Channel.BANK]
 * mixes several banks and accounts under one channel, and a Transaction does not say which
 * account it moved, so a chain across them would break on every switch and bury real
 * breaks in noise. [Channel.CASH] has no stated balance at all.
 *
 * ### Fees
 *
 * An outgoing transfer can cost a fee the message does not itemise, so the balance drops
 * by a little more than the amount. A balance that is *lower* than predicted, by no more
 * than [feeAllowanceMinor] for each outgoing transaction since the last consistent
 * balance, is taken as fees. A balance that is *higher* than predicted is never excused:
 * no fee makes money appear, and money in is what reconciliation is looking for.
 */
object BalanceChain {

    val CHAINED_CHANNELS = setOf(Channel.TELEBIRR, Channel.CBE_BIRR)

    /** Allowance per outgoing transaction: the larger of this floor and [FEE_PERCENT]. */
    const val FEE_FLOOR_MINOR = 1_000L

    /** Generous on purpose. A missed small outgoing payment matters less than a false alarm. */
    const val FEE_PERCENT = 2L

    fun feeAllowanceMinor(amountMinor: Long): Long =
        maxOf(FEE_FLOOR_MINOR, amountMinor * FEE_PERCENT / 100)

    fun findBreaks(transactions: List<Transaction>): List<BalanceBreak> =
        transactions
            .filter { !it.isDeleted && it.channel in CHAINED_CHANNELS }
            .groupBy { it.channel }
            .flatMap { (channel, rows) -> walk(channel, rows) }
            .sortedBy { it.beforeAt }

    private fun walk(channel: Channel, rows: List<Transaction>): List<BalanceBreak> {
        val breaks = mutableListOf<BalanceBreak>()
        // Null until a balance is known: before the first stated balance, and again
        // after a row whose place in the chain is in doubt.
        var predicted: Long? = null
        var lastConsistentAt = 0L
        var feeAllowance = 0L

        for (row in inChainOrder(rows)) {
            if (row.isPossibleDuplicate) {
                // A flagged row is either a second copy (so its amount must not count) or
                // a second real payment (so it must). Comparing either way would report a
                // break that is really the duplicate question, which reconcile already
                // asks. Start again from whatever this row states.
                predicted = row.balanceAfterMinor
                lastConsistentAt = row.occurredAt
                feeAllowance = 0L
                continue
            }

            val expected = predicted?.plus(signed(row))
            if (row.direction == Direction.OUT) feeAllowance += feeAllowanceMinor(row.amountMinor)
            val stated = row.balanceAfterMinor

            if (stated == null) {
                // No balance on this one; it still moves the prediction.
                predicted = expected
                continue
            }
            if (expected != null) {
                val difference = stated - expected
                val isFee = difference < 0 && -difference <= feeAllowance
                if (difference != 0L && !isFee) {
                    breaks += BalanceBreak(channel, lastConsistentAt, row.occurredAt, difference)
                }
            }
            predicted = stated
            lastConsistentAt = row.occurredAt
            feeAllowance = 0L
        }
        return breaks
    }

    /**
     * Oldest first. Message timestamps are often to the minute, so two payments in one
     * minute tie, and the wrong order of the two would show as two breaks that cancel.
     * Within a tie, the row that follows from the current balance goes first.
     */
    private fun inChainOrder(rows: List<Transaction>): List<Transaction> {
        val ordered = mutableListOf<Transaction>()
        var balance: Long? = null
        rows.sortedWith(compareBy({ it.occurredAt }, { it.id }))
            .groupBy { it.occurredAt }
            .values
            .forEach { tied ->
                val pending = tied.toMutableList()
                while (pending.isNotEmpty()) {
                    val next = pending.firstOrNull { row ->
                        val b = balance
                        b != null && row.balanceAfterMinor == b + signed(row)
                    } ?: pending.first()
                    pending.remove(next)
                    ordered += next
                    balance = next.balanceAfterMinor ?: balance?.plus(signed(next))
                }
            }
        return ordered
    }

    private fun signed(row: Transaction): Long =
        if (row.direction == Direction.IN) row.amountMinor else -row.amountMinor
}
