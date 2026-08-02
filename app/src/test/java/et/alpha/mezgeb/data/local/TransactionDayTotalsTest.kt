package et.alpha.mezgeb.data.local

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The ledger's day headers.
 *
 * The grouping happens in SQLite with `localtime`, and the app computes the same
 * boundary in Kotlin with [MezgebDates] to look the totals up. If those two ever
 * disagree, the subtotal on a header belongs to a different day than the rows under
 * it — which is exactly the sort of quietly wrong number this app exists to avoid.
 */
class TransactionDayTotalsTest : DatabaseTest() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** The same offset the repository passes in production. */
    private val offset: Long = zone.rules.getOffset(Instant.now()).totalSeconds * 1000L

    private val dao get() = db.transactionDao()

    @Test
    fun `totals are grouped by day and split by direction`() = runTest {
        dao.insertAll(
            listOf(
                transaction("a", direction = Direction.IN, amountMinor = 45_000, occurredAt = T0 + HOUR),
                transaction("b", direction = Direction.IN, amountMinor = 5_000, occurredAt = T0 + 2 * HOUR),
                transaction("c", direction = Direction.OUT, amountMinor = 12_000, occurredAt = T0 + 3 * HOUR),
                transaction("d", direction = Direction.IN, amountMinor = 1_000, occurredAt = T0 + DAY + HOUR),
            ),
        )

        val totals = dao.observeDailyTotals(offset, null, null, null, null, null, null, null).first()

        assertEquals(2, totals.size)
        // Newest day first, matching the list order.
        assertEquals(MezgebDates.startOfDay(T0 + DAY + HOUR, zone), totals[0].dayEpochMillis)
        assertEquals(1_000L, totals[0].inMinor)
        assertEquals(0L, totals[0].outMinor)

        assertEquals(MezgebDates.startOfDay(T0 + HOUR, zone), totals[1].dayEpochMillis)
        assertEquals(50_000L, totals[1].inMinor)
        assertEquals(12_000L, totals[1].outMinor)
    }

    @Test
    fun `the day key matches what the app computes for the same instant`() = runTest {
        // The assertion that keeps SQLite's `localtime` and MezgebDates in step.
        val occurredAt = T0 + 21 * HOUR
        dao.insert(transaction("a", occurredAt = occurredAt))

        val totals = dao.observeDailyTotals(offset, null, null, null, null, null, null, null).first()

        assertEquals(MezgebDates.startOfDay(occurredAt, zone), totals.single().dayEpochMillis)
    }

    @Test
    fun `the same filters as the list apply to the subtotals`() = runTest {
        dao.insertAll(
            listOf(
                transaction("a", channel = Channel.CASH, amountMinor = 45_000, occurredAt = T0 + HOUR),
                transaction("b", channel = Channel.TELEBIRR, amountMinor = 5_000, occurredAt = T0 + HOUR),
            ),
        )

        val cashOnly = dao.observeDailyTotals(
            offsetMillis = offset,
            from = null,
            to = null,
            channel = Channel.CASH,
            direction = null,
            categoryId = null,
            isReconciled = null,
            query = null,
        ).first()

        // Otherwise a filtered list would show a subtotal for rows it is not showing.
        assertEquals(45_000L, cashOnly.single().inMinor)
    }

    @Test
    fun `deleted rows are not counted`() = runTest {
        dao.insertAll(
            listOf(
                transaction("a", amountMinor = 45_000, occurredAt = T0 + HOUR),
                transaction("b", amountMinor = 5_000, occurredAt = T0 + HOUR, isDeleted = true),
            ),
        )

        val totals = dao.observeDailyTotals(offset, null, null, null, null, null, null, null).first()

        assertEquals(45_000L, totals.single().inMinor)
    }

    @Test
    fun `an empty ledger has no days`() = runTest {
        assertTrue(
            dao.observeDailyTotals(offset, null, null, null, null, null, null, null).first().isEmpty(),
        )
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
