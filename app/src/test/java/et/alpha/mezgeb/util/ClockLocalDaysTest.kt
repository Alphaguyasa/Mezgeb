package et.alpha.mezgeb.util

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The midnight timer behind Today. The clock is tied to the test scheduler's virtual
 * time, so advancing one advances the other, and a day passes in no real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockLocalDaysTest {

    /** Addis Ababa's offset, fixed so the test does not depend on the machine's zone. */
    private val zone: ZoneId = ZoneOffset.ofHours(3)

    /** One minute to local midnight. */
    private val start = Instant.parse("2026-08-01T20:59:00Z")

    @Test
    fun `emits today at once, and the next day at local midnight`() = runTest {
        val days = ClockLocalDays(virtualClock(), zone)
        val today = MezgebDates.dayBounds(start.toEpochMilli(), zone)

        days.observe().test {
            assertEquals(today, awaitItem())

            // One millisecond before midnight it is still today.
            advanceTimeBy(59_999L)
            expectNoEvents()

            advanceTimeBy(2L)
            val tomorrow = awaitItem()
            assertEquals(today.last + 1, tomorrow.first)
            assertEquals(MezgebDates.dayBounds(tomorrow.first, zone), tomorrow)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `after the first midnight it waits a whole day for the next`() = runTest {
        val days = ClockLocalDays(virtualClock(), zone)

        days.observe().test {
            awaitItem()
            advanceTimeBy(60_001L)
            val second = awaitItem()

            advanceTimeBy(MezgebDates.DAY_MILLIS - 2)
            expectNoEvents()

            advanceTimeBy(2L)
            assertEquals(second.last + 1, awaitItem().first)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /** [start] plus however much virtual time the test has advanced. */
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
    }
}
