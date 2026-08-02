package et.alpha.mezgeb.domain.report

import et.alpha.mezgeb.data.local.DAY
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

/**
 * Aggregation against a fixed dataset.
 *
 * The numbers below are hand-computed from the fixture rather than read out of the
 * implementation, which is the only way this test can catch the implementation being
 * wrong.
 */
class BuildReportUseCaseTest : DatabaseTest() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Local midnight of the fixture's first day, so day bucketing lines up. */
    private val day0 = MezgebDates.startOfDay(T0 + 12 * HOUR, zone)
    private val periodStart = day0
    private val periodEnd = day0 + 3 * DAY - 1

    private lateinit var useCase: BuildReportUseCase

    @Before
    fun setUp() {
        useCase = BuildReportUseCase(
            transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao()),
            customers = CustomerRepository(db.customerDao(), db.creditEntryDao()),
            rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao()),
            zone = zone,
        )
    }

    /**
     * Three days:
     *  day 0 — in 450 (telebirr, Sales), in 50 (cash, no category), out 120 (bank)
     *  day 1 — nothing
     *  day 2 — in 300 (bank, Sales)
     */
    private suspend fun seedFixture() {
        db.categoryDao().insert(category("cat_sales", name = "Sales"))
        db.transactionDao().insertAll(
            listOf(
                transaction("a", direction = Direction.IN, amountMinor = 45_000, channel = Channel.TELEBIRR, categoryId = "cat_sales", occurredAt = day0 + HOUR),
                transaction("b", direction = Direction.IN, amountMinor = 5_000, channel = Channel.CASH, categoryId = null, occurredAt = day0 + 2 * HOUR),
                transaction("c", direction = Direction.OUT, amountMinor = 12_000, channel = Channel.BANK, categoryId = "cat_sales", occurredAt = day0 + 3 * HOUR),
                transaction("d", direction = Direction.IN, amountMinor = 30_000, channel = Channel.BANK, categoryId = "cat_sales", occurredAt = day0 + 2 * DAY + HOUR),
            ),
        )
    }

    @Test
    fun `totals and count come out of the fixture as computed by hand`() = runTest {
        seedFixture()

        val report = useCase(periodStart, periodEnd)

        assertEquals(80_000L, report.totalInMinor) // 450 + 50 + 300
        assertEquals(12_000L, report.totalOutMinor)
        assertEquals(68_000L, report.netMinor)
        assertEquals(4, report.transactionCount)
        assertTrue(report.hasEnoughData)
    }

    @Test
    fun `channel shares are of money in, and add to one`() = runTest {
        seedFixture()

        val report = useCase(periodStart, periodEnd)
        val byLabel = report.byChannel.associate { it.label to it }

        // Mixing directions into one denominator would make shares sum past 100% and
        // mean nothing.
        assertEquals(45_000L, byLabel.getValue("TELEBIRR").amountMinor)
        assertEquals(30_000L, byLabel.getValue("BANK").amountMinor)
        assertEquals(5_000L, byLabel.getValue("CASH").amountMinor)
        assertEquals(1.0f, report.byChannel.sumOf { it.share.toDouble() }.toFloat(), 0.001f)
        assertEquals(0.5625f, byLabel.getValue("TELEBIRR").share, 0.001f)
    }

    @Test
    fun `money with no category is its own slice, not dropped`() = runTest {
        // Dropping it would leave the percentages not adding up, and "nobody has said
        // what this was" is a real answer worth showing.
        seedFixture()

        val report = useCase(periodStart, periodEnd)

        assertEquals(2, report.byCategory.size)
        val uncategorised = report.byCategory.single { it.label == "—" }
        assertEquals(5_000L, uncategorised.amountMinor)
        assertEquals(75_000L, report.byCategory.single { it.label == "Sales" }.amountMinor)
    }

    @Test
    fun `every day in the period is present, including the quiet ones`() = runTest {
        // A chart with the empty days missing lies about the shape of a week.
        seedFixture()

        val report = useCase(periodStart, periodEnd)

        assertEquals(3, report.dailyNet.size)
        assertEquals(listOf(38_000L, 0L, 30_000L), report.dailyNet.map { it.netMinor })
        assertEquals(day0, report.dailyNet.first().dayEpochMillis)
    }

    @Test
    fun `a day that paid out more than it took has a negative net`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("in", direction = Direction.IN, amountMinor = 5_000, occurredAt = day0 + HOUR),
                transaction("out", direction = Direction.OUT, amountMinor = 20_000, occurredAt = day0 + 2 * HOUR),
                transaction("other", direction = Direction.IN, amountMinor = 1_000, occurredAt = day0 + 3 * HOUR),
            ),
        )

        val report = useCase(periodStart, periodEnd)

        assertEquals(-14_000L, report.dailyNet.first().netMinor)
    }

    @Test
    fun `transactions outside the period are not counted`() = runTest {
        seedFixture()
        db.transactionDao().insert(
            transaction("outside", amountMinor = 99_000, occurredAt = periodEnd + 1),
        )

        assertEquals(4, useCase(periodStart, periodEnd).transactionCount)
    }

    @Test
    fun `fewer than three transactions is not enough to summarise`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("a", amountMinor = 45_000, occurredAt = day0 + HOUR),
                transaction("b", amountMinor = 5_000, occurredAt = day0 + 2 * HOUR),
            ),
        )

        val report = useCase(periodStart, periodEnd)

        assertEquals(2, report.transactionCount)
        assertFalse(report.hasEnoughData)
    }

    @Test
    fun `top debtors are the five who owe most, and nobody who owes nothing`() = runTest {
        listOf(
            "c1" to 90_000L,
            "c2" to 50_000L,
            "c3" to 70_000L,
            "c4" to 10_000L,
            "c5" to 30_000L,
            "c6" to 20_000L,
        ).forEach { (id, amount) ->
            db.customerDao().insert(customer(id, name = "Customer $id"))
            db.creditEntryDao().insert(
                creditEntry("${id}_given", id, CreditKind.CREDIT_GIVEN, amount, occurredAt = day0),
            )
        }
        // Settled up: owes nothing, so does not belong on a list of debtors.
        db.customerDao().insert(customer("paid", name = "Settled"))
        db.creditEntryDao().insert(creditEntry("g", "paid", CreditKind.CREDIT_GIVEN, 5_000, occurredAt = day0))
        db.creditEntryDao().insert(creditEntry("r", "paid", CreditKind.REPAYMENT, 5_000, occurredAt = day0))

        val report = useCase(periodStart, periodEnd)

        assertEquals(5, report.topDebtors.size)
        assertEquals(
            listOf(90_000L, 70_000L, 50_000L, 30_000L, 20_000L),
            report.topDebtors.map { it.balanceMinor },
        )
        assertTrue(report.topDebtors.none { it.customer.name == "Settled" })
    }

    @Test
    fun `an empty period reports zero rather than failing`() = runTest {
        val report = useCase(periodStart, periodEnd)

        assertEquals(0L, report.totalInMinor)
        assertEquals(0, report.transactionCount)
        assertFalse(report.hasEnoughData)
        // The days still exist; the chart draws a flat line rather than nothing.
        assertEquals(3, report.dailyNet.size)
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
