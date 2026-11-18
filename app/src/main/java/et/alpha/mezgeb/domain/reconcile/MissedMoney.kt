package et.alpha.mezgeb.domain.reconcile

import kotlin.math.abs

/**
 * The week's "money you may have missed", taken from the same explanations the
 * reconcile screen lists, so the card on Today and the screen behind it never disagree.
 *
 * Two kinds of money are kept apart on purpose:
 * - [possiblyMissingMinor] is money that may be absent from the ledger: balance jumps
 *   no recorded transaction explains, and the amounts found in messages no rule could
 *   read. This is the figure the card leads with.
 * - [possibleDuplicatesMinor] is money that may be in the ledger twice. Adding it to
 *   the first figure would mix "too little" with "too much", so it is its own line.
 *
 * Uncategorised money and outstanding credit are left out: they are recorded, only not
 * yet sorted or not yet paid, and the reconcile screen already names them.
 */
data class MissedMoney(
    val balanceJumpCount: Int = 0,
    val balanceJumpMinor: Long = 0L,
    val unreadMessageCount: Int = 0,
    /** Of [unreadMessageCount], how many carried an amount the sniffer could read. */
    val unreadAmountsFound: Int = 0,
    val unreadMinor: Long = 0L,
    val possibleDuplicateCount: Int = 0,
    val possibleDuplicatesMinor: Long = 0L,
) {
    val possiblyMissingMinor: Long get() = balanceJumpMinor + unreadMinor

    /** Nothing to show: the card stays hidden rather than announcing zero. */
    val isEmpty: Boolean
        get() = balanceJumpCount == 0 && unreadMessageCount == 0 && possibleDuplicateCount == 0

    companion object {
        fun from(explanations: List<GapExplanation>): MissedMoney =
            explanations.fold(MissedMoney()) { acc, explanation ->
                when (explanation) {
                    is GapExplanation.UnrecordedBalanceChange -> acc.copy(
                        balanceJumpCount = acc.balanceJumpCount + 1,
                        // A jump either way is money the ledger cannot account for.
                        balanceJumpMinor = acc.balanceJumpMinor + abs(explanation.differenceMinor),
                    )
                    is GapExplanation.UnmatchedMessages -> acc.copy(
                        unreadMessageCount = acc.unreadMessageCount + explanation.count,
                        unreadAmountsFound = acc.unreadAmountsFound + explanation.amountsFound,
                        unreadMinor = acc.unreadMinor + (explanation.partialTotalMinor ?: 0L),
                    )
                    is GapExplanation.PossibleDuplicates -> acc.copy(
                        possibleDuplicateCount = acc.possibleDuplicateCount + explanation.count,
                        possibleDuplicatesMinor = acc.possibleDuplicatesMinor + explanation.totalMinor,
                    )
                    else -> acc
                }
            }
    }
}
