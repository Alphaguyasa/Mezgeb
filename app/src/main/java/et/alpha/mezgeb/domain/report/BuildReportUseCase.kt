package et.alpha.mezgeb.domain.report

import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CustomerBalance
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import javax.inject.Inject

/** The periods a report is asked for. */
enum class ReportPeriod {
    DAY,
    WEEK,
    MONTH,
    CUSTOM,
}

/**
 * One slice of a breakdown.
 *
 * @param share of the total, 0..1. Computed here rather than in the UI so the
 *   percentages on screen and the percentages in an export cannot disagree.
 */
data class BreakdownSlice(
    val label: String,
    val amountMinor: Long,
    val share: Float,
    val transactionCount: Int,
)

/** Net for one day, for the chart. Days with no activity are present and zero. */
data class DailyNet(val dayEpochMillis: Long, val netMinor: Long)

data class Report(
    val periodStart: Long,
    val periodEnd: Long,
    val totalInMinor: Long,
    val totalOutMinor: Long,
    val transactionCount: Int,
    val byChannel: List<BreakdownSlice>,
    val byCategory: List<BreakdownSlice>,
    val dailyNet: List<DailyNet>,
    val topDebtors: List<CustomerBalance>,
) {
    val netMinor: Long get() = totalInMinor - totalOutMinor

    /**
     * Below this, a summary is arithmetic on noise: three transactions make a
     * "breakdown by category" that is really just a list of three transactions.
     */
    val hasEnoughData: Boolean get() = transactionCount >= MINIMUM_TRANSACTIONS

    companion object {
        const val MINIMUM_TRANSACTIONS = 3
        const val TOP_DEBTOR_COUNT = 5
    }
}

/**
 * Everything the reports screen shows, computed once.
 *
 * Aggregation happens in SQL wherever a total is wanted, and in Kotlin only where the
 * shape has to change — filling in the empty days the chart needs, and turning
 * category ids into names. A report that had to load every transaction to draw a bar
 * chart would get slower every month a shop uses the app.
 */
class BuildReportUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val customers: CustomerRepository,
    private val rules: RuleRepository,
    private val zone: ZoneId,
) {

    suspend operator fun invoke(periodStart: Long, periodEnd: Long): Report {
        val totals = transactions.periodTotals(periodStart, periodEnd)
        val count = transactions.countInPeriod(periodStart, periodEnd)

        return Report(
            periodStart = periodStart,
            periodEnd = periodEnd,
            totalInMinor = totals.capturedInMinor,
            totalOutMinor = totals.capturedOutMinor,
            transactionCount = count,
            byChannel = channelBreakdown(periodStart, periodEnd),
            byCategory = categoryBreakdown(periodStart, periodEnd),
            dailyNet = dailyNet(periodStart, periodEnd),
            topDebtors = customers.observeCustomersWithBalance().first()
                .filter { it.owesMoney }
                .take(Report.TOP_DEBTOR_COUNT),
        )
    }

    /**
     * Percentages are of money in, not of everything.
     *
     * Mixing directions into one denominator produces shares that sum past 100% and
     * mean nothing: "sales were 40%" of what, if the other 60% includes money leaving?
     */
    private suspend fun channelBreakdown(from: Long, to: Long): List<BreakdownSlice> {
        val totals = transactions.totalsByChannel(Direction.IN, from, to)
        val total = totals.sumOf { it.totalMinor }
        return totals.map { row ->
            BreakdownSlice(
                label = row.channel.name,
                amountMinor = row.totalMinor,
                share = share(row.totalMinor, total),
                transactionCount = row.transactionCount,
            )
        }
    }

    private suspend fun categoryBreakdown(from: Long, to: Long): List<BreakdownSlice> {
        val names = rules.getCategories().associate { it.id to it.name }
        val totals = transactions.totalsByCategory(Direction.IN, from, to)
        val total = totals.sumOf { it.totalMinor }
        return totals.map { row ->
            BreakdownSlice(
                // A null category id is real and common — it means nobody has said what
                // this money was. Reporting it as its own slice is more useful than
                // dropping it and leaving the percentages not adding up.
                label = row.categoryId?.let { names[it] } ?: UNCATEGORISED,
                amountMinor = row.totalMinor,
                share = share(row.totalMinor, total),
                transactionCount = row.transactionCount,
            )
        }
    }

    /**
     * One entry per day in the period, including the days nothing happened.
     *
     * A chart with the empty days missing is a chart that lies about the shape of a
     * week: four bars evenly spaced reads as four steady days, not as Monday, Tuesday,
     * Friday and Sunday.
     */
    private suspend fun dailyNet(from: Long, to: Long): List<DailyNet> {
        val byDay = transactions
            .observeDailyTotals(TransactionFilter(from = from, to = to), zone)
            .first()
            .associate { it.dayEpochMillis to (it.inMinor - it.outMinor) }

        val firstDay = MezgebDates.startOfDay(from, zone)
        val lastDay = MezgebDates.startOfDay(to, zone)

        return generateSequence(firstDay) { day ->
            (day + MezgebDates.DAY_MILLIS).takeIf { it <= lastDay }
        }.map { day -> DailyNet(day, byDay[day] ?: 0L) }.toList()
    }

    private fun share(part: Long, total: Long): Float =
        if (total == 0L) 0f else part.toFloat() / total

    /** Bounds for a named period, ending today. */
    fun boundsFor(period: ReportPeriod, now: Long, custom: LongRange? = null): LongRange {
        val today = MezgebDates.dayBounds(now, zone)
        return when (period) {
            ReportPeriod.DAY -> today
            ReportPeriod.WEEK -> (today.first - 6 * MezgebDates.DAY_MILLIS)..today.last
            ReportPeriod.MONTH -> (today.first - 29 * MezgebDates.DAY_MILLIS)..today.last
            ReportPeriod.CUSTOM -> custom ?: today
        }
    }

    private companion object {
        const val UNCATEGORISED = "—"
    }
}
