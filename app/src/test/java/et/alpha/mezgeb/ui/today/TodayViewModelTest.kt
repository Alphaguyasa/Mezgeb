package et.alpha.mezgeb.ui.today

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TodayViewModelTest {

    private val fixedInstant = Instant.parse("2026-08-01T09:30:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @Test
    fun `emits loaded state seeded from the injected clock`() = runTest {
        val viewModel = TodayViewModel(clock)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(fixedInstant.toEpochMilli(), state.nowMillis)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `refresh re-reads the clock`() = runTest {
        val viewModel = TodayViewModel(clock)

        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(TodayEvent.Refresh)
            // Same fixed clock, so the value is unchanged and no new state is emitted.
            expectNoEvents()
            assertEquals(fixedInstant.toEpochMilli(), viewModel.state.value.nowMillis)
            cancelAndConsumeRemainingEvents()
        }
    }
}
