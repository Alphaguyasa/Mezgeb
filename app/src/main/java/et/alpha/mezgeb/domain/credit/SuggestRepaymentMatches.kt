package et.alpha.mezgeb.domain.credit

import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.Transaction
import javax.inject.Inject

/**
 * A captured payment offered as the source of a repayment, and why.
 *
 * The reason is shown to the user rather than kept internal: "same amount" is a claim
 * they can check at a glance, and a ranked list with no stated reason is a list people
 * either trust blindly or ignore entirely.
 */
data class RepaymentMatch(
    val transaction: Transaction,
    val matchesAmount: Boolean,
    val matchesPhone: Boolean,
) {
    /** Amount first, phone second, per the credit book's own priority. */
    val score: Int
        get() = (if (matchesAmount) AMOUNT_WEIGHT else 0) + (if (matchesPhone) PHONE_WEIGHT else 0)

    private companion object {
        const val AMOUNT_WEIGHT = 2
        const val PHONE_WEIGHT = 1
    }
}

/**
 * Ranks recent unlinked payments against a repayment the shop is recording.
 *
 * Nothing is ever linked automatically. A wrongly linked repayment makes a debt
 * disappear against a payment that was for something else, and the shop finds out at
 * reconciliation, weeks later, with no way to tell which of the two records is wrong.
 * So this suggests, in an order, and a person chooses.
 */
class SuggestRepaymentMatchesUseCase @Inject constructor() {

    fun rank(
        candidates: List<Transaction>,
        customer: Customer,
        amountMinor: Long,
    ): List<RepaymentMatch> {
        val phone = customer.phone?.let(::normalisePhone)
        return candidates
            .map { transaction ->
                RepaymentMatch(
                    transaction = transaction,
                    matchesAmount = transaction.amountMinor == amountMinor,
                    matchesPhone = phone != null &&
                        transaction.counterpartyPhone?.let(::normalisePhone) == phone,
                )
            }
            // Best first; equal candidates fall back to most recent, which is the next
            // most useful thing to know.
            .sortedWith(
                compareByDescending<RepaymentMatch> { it.score }
                    .thenByDescending { it.transaction.occurredAt },
            )
    }

    /**
     * Compares phone numbers by their last nine digits.
     *
     * `+251911234567`, `0911234567` and `911234567` are one number written three ways,
     * and which form arrives depends on the provider that sent the message.
     */
    private fun normalisePhone(raw: String): String =
        raw.filter { it.isDigit() }.takeLast(SIGNIFICANT_DIGITS)

    private companion object {
        const val SIGNIFICANT_DIGITS = 9

        /** A week, per the session brief. Older payments are not plausible matches. */
        const val DEFAULT_WINDOW_DAYS = 7
    }
}
