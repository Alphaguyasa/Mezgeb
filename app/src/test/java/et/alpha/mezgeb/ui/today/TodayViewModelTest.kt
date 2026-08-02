package et.alpha.mezgeb.ui.today

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.capture.CaptureHealth
import et.alpha.mezgeb.capture.CaptureHealthMonitor
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.capture.vendor.BatteryOptimizationHelper
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class TodayViewModelTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Mid-morning, so "today" has room on both sides of it in local time. */
    private val now = Instant.parse("2026-08-01T09:30:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private val access = FakeCaptureAccess()
    private lateinit var settingsHarness: SettingsHarness
    private lateinit var transactions: TransactionRepository
    private lateinit var rules: RuleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsHarness = SettingsHarness(tempFolder.root)
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the first state is loading and claims no totals`() = runTest {
        val state = viewModel().state.value

        assertTrue(state.isLoading)
        assertEquals(0L, state.totalInMinor)
    }

    @Test
    fun `an empty ledger is empty, and says capture has never worked`() = runTest {
        viewModel().state.test {
            val state = awaitLoaded()
            assertTrue(state.isEmpty)
            // The distinction the two empty-state messages are built on.
            assertFalse(state.hasEverCaptured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `today's totals count today and nothing else`() = runTest {
        val today = MezgebDates.dayBounds(now, zone)
        db.transactionDao().insertAll(
            listOf(
                transaction("t1", direction = Direction.IN, amountMinor = 45_000, occurredAt = today.first + 1),
                transaction("t2", direction = Direction.IN, amountMinor = 5_000, occurredAt = today.last - 1),
                transaction("t3", direction = Direction.OUT, amountMinor = 12_000, occurredAt = today.first + 2),
                // Yesterday, and therefore none of today's business.
                transaction("t4", direction = Direction.IN, amountMinor = 99_000, occurredAt = today.first - 1),
            ),
        )

        viewModel().state.test {
            val state = awaitItemWhere { it.totalInMinor > 0 }
            assertEquals(50_000L, state.totalInMinor)
            assertEquals(12_000L, state.totalOutMinor)
            assertEquals(38_000L, state.netMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the recent list carries category names, not category ids`() = runTest {
        db.categoryDao().insert(category("cat_sales", name = "Sales"))
        db.transactionDao().insert(
            transaction("t1", categoryId = "cat_sales", channel = Channel.TELEBIRR, occurredAt = now),
        )

        viewModel().state.test {
            val state = awaitItemWhere { it.recent.isNotEmpty() }
            assertEquals("Sales", state.recent.single().categoryName)
            assertTrue(state.hasEverCaptured)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `revoked access surfaces as a health state, not as an empty ledger`() = runTest {
        access.granted = false

        viewModel().state.test {
            assertEquals(CaptureHealth.NO_ACCESS, awaitLoaded().captureHealth)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh re-checks access after the user has been to settings`() = runTest {
        access.granted = false
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(CaptureHealth.NO_ACCESS, awaitLoaded().captureHealth)

            access.granted = true
            viewModel.onEvent(TodayEvent.Refresh)

            assertEquals(CaptureHealth.HEALTHY, awaitItemWhere { it.captureHealth != CaptureHealth.NO_ACCESS }.captureHealth)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the queue count is what the review badge shows`() = runTest {
        db.rawMessageDao().insertAllIgnoringDuplicates(
            listOf(
                rawMessage("m1", parseState = ParseState.UNMATCHED),
                rawMessage("m2", parseState = ParseState.UNMATCHED),
            ),
        )

        viewModel().state.test {
            assertEquals(2, awaitItemWhere { it.reviewQueueCount > 0 }.reviewQueueCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the banner's fix action depends on what is wrong`() {
        val viewModel = viewModel()

        // No access is fixed in notification settings; silence is a battery problem,
        // and sending the user to the wrong screen is worse than sending them nowhere.
        assertEquals("test.settings", viewModel.healthFixIntent(CaptureHealth.NO_ACCESS)?.action)
        assertEquals(null, viewModel.healthFixIntent(CaptureHealth.HEALTHY))
    }

    private fun viewModel() = TodayViewModel(
        transactions = transactions,
        rules = rules,
        captureHealth = CaptureHealthMonitor(settingsHarness.repository, access, clock),
        access = access,
        battery = BatteryOptimizationHelper(ApplicationProvider.getApplicationContext<Context>()),
        clock = clock,
        zone = zone,
    )

    private suspend fun ReceiveTurbine<TodayUiState>.awaitLoaded(): TodayUiState =
        awaitItemWhere { !it.isLoading }

    private suspend fun ReceiveTurbine<TodayUiState>.awaitItemWhere(
        predicate: (TodayUiState) -> Boolean,
    ): TodayUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    private class FakeCaptureAccess(var granted: Boolean = true) : CaptureAccess {
        override fun isNotificationAccessGranted(): Boolean = granted
        override fun settingsIntent(): Intent = Intent("test.settings")
        override fun requestRebind() = Unit
    }
}
