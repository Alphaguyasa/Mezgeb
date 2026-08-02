package et.alpha.mezgeb.domain.reconcile

import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.parser.normalize.AmountSniffer
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Every figure in a period, and what the difference might be.
 *
 * @param expectedTotalMinor null until the shop states what it thinks it took. The
 *   gap is meaningless without it, so [gapMinor] is null too.
 */
data class PeriodReconciliation(
    val periodStart: Long,
    val periodEnd: Long,
    val expectedTotalMinor: Long?,
    /** Money in through a captured message. Hand-entered cash is counted separately. */
    val capturedInMinor: Long,
    val capturedOutMinor: Long,
    val cashRecordedMinor: Long,
    val creditGivenMinor: Long,
    val creditRepaidMinor: Long,
    val explanations: List<GapExplanation> = emptyList(),
) {
    val accountedForMinor: Long
        get() = capturedInMinor + cashRecordedMinor + creditGivenMinor - creditRepaidMinor

    val gapMinor: Long? get() = expectedTotalMinor?.let { it - accountedForMinor }

    /** Balanced to the santim. The moment the app exists for. */
    val isBalanced: Boolean get() = gapMinor == 0L

    /** The shop took less than it thought, or recorded more than it took. */
    val isShort: Boolean get() = (gapMinor ?: 0L) > 0
}

/**
 * Something that could account for part of a gap.
 *
 * @param potentialMinor how much of the gap this could explain, where that is
 *   knowable. Null means "this is a real possibility with no number attached" — an
 *   edit, for instance — and those rank last rather than as zero.
 */
sealed interface GapExplanation {

    val potentialMinor: Long?

    /** Messages in the period no rule could read. The amount is a guess; see [AmountSniffer]. */
    data class UnmatchedMessages(
        val count: Int,
        val partialTotalMinor: Long?,
        val amountsFound: Int,
    ) : GapExplanation {
        override val potentialMinor: Long? get() = partialTotalMinor
    }

    data class UncategorizedTransactions(
        val count: Int,
        val totalMinor: Long,
    ) : GapExplanation {
        override val potentialMinor: Long get() = totalMinor
    }

    data class OutstandingCredit(
        val customerCount: Int,
        val totalMinor: Long,
    ) : GapExplanation {
        override val potentialMinor: Long get() = totalMinor
    }

    /** Someone changed a parsed value after this period was opened. */
    data class EditedSincePeriodOpened(val count: Int) : GapExplanation {
        override val potentialMinor: Long? get() = null
    }
}

/**
 * The reconciliation arithmetic, from 03-ARCHITECTURE.md section 7.
 *
 * ### One correction to the documented formula
 *
 * The architecture writes:
 *
 * ```
 * capturedIn   = Σ transactions where direction=IN
 * cashRecorded = Σ transactions where channel=CASH and direction=IN
 * accountedFor = capturedIn + cashRecorded + creditGiven - creditRepaid
 * ```
 *
 * Taken literally, a cash sale is inside `capturedIn` *and* inside `cashRecorded`, so
 * every hand-entered cash sale is counted twice and the shop is told it is short by
 * exactly the cash it took. That is the most damaging possible answer from a
 * reconciliation screen: it sends someone looking for money that is not missing.
 *
 * The glossary settles the intent — "captured" means "arrived as a message", and a
 * cash entry has no message — so `capturedIn` here excludes `CASH` and the two terms
 * add up to money in, once. `ReconcilePeriodUseCaseTest` asserts the double count
 * does not happen.
 */
class ReconcilePeriodUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val customers: CustomerRepository,
    private val expectedSales: ExpectedSalesProvider,
) {

    suspend operator fun invoke(
        periodStart: Long,
        periodEnd: Long,
        /** When the period was opened, for the "edited since" explanation. */
        openedAt: Long,
    ): PeriodReconciliation {
        val totals = transactions.periodTotals(periodStart, periodEnd)
        val capturedIn = transactions.sumCapturedIn(periodStart, periodEnd)
        val creditGiven = customers.sumByKind(CreditKind.CREDIT_GIVEN, periodStart, periodEnd)
        val creditRepaid = customers.sumByKind(CreditKind.REPAYMENT, periodStart, periodEnd)

        val figures = PeriodReconciliation(
            periodStart = periodStart,
            periodEnd = periodEnd,
            expectedTotalMinor = expectedSales.expectedTotalMinor(periodStart, periodEnd),
            capturedInMinor = capturedIn,
            capturedOutMinor = totals.capturedOutMinor,
            cashRecordedMinor = totals.cashRecordedMinor,
            creditGivenMinor = creditGiven,
            creditRepaidMinor = creditRepaid,
        )

        return figures.copy(explanations = explain(periodStart, periodEnd, openedAt))
    }

    /**
     * A gap is a number; this is the list of things it could be.
     *
     * Ranked by how much each could account for, because a shop owner with five
     * minutes should look at the one worth five minutes. Explanations with no
     * knowable amount rank last — not as zero, which would claim they explain
     * nothing.
     */
    private suspend fun explain(
        periodStart: Long,
        periodEnd: Long,
        openedAt: Long,
    ): List<GapExplanation> {
        val explanations = mutableListOf<GapExplanation>()

        val unmatched = transactions.getUnmatchedBetween(periodStart, periodEnd)
        if (unmatched.isNotEmpty()) {
            val bodies = unmatched.map { it.body }
            val found = AmountSniffer.sniffedCount(bodies)
            explanations += GapExplanation.UnmatchedMessages(
                count = unmatched.size,
                partialTotalMinor = AmountSniffer.sniffTotal(bodies).takeIf { found > 0 },
                amountsFound = found,
            )
        }

        val uncategorised = transactions.countUncategorised(periodStart, periodEnd)
        if (uncategorised > 0) {
            explanations += GapExplanation.UncategorizedTransactions(
                count = uncategorised,
                totalMinor = transactions.sumUncategorised(periodStart, periodEnd),
            )
        }

        val outstanding = customers.observeTotalOutstandingMinor().first()
        val inDebt = customers.observeCustomersInDebtCount().first()
        if (outstanding > 0) {
            explanations += GapExplanation.OutstandingCredit(inDebt, outstanding)
        }

        val edited = transactions.countEditedSince(openedAt, periodStart, periodEnd)
        if (edited > 0) {
            explanations += GapExplanation.EditedSincePeriodOpened(edited)
        }

        // nullsFirst reversed puts the unknowable ones last while keeping the rest in
        // descending order. nullsLast reversed would put them first, which is the
        // opposite of what "ranked by how much it could explain" means.
        return explanations.sortedWith(
            compareByDescending(nullsFirst()) { it.potentialMinor },
        )
    }
}
