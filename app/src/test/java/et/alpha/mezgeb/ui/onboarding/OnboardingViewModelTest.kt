package et.alpha.mezgeb.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.capture.sms.SmsImportStatus
import et.alpha.mezgeb.capture.sms.SmsImporter
import et.alpha.mezgeb.capture.vendor.BatteryOptimizationHelper
import et.alpha.mezgeb.data.settings.SettingsHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val access = FakeCaptureAccess()
    private val importer = FakeSmsImporter()
    private lateinit var settingsHarness: SettingsHarness
    private lateinit var battery: BatteryOptimizationHelper

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsHarness = SettingsHarness(tempFolder.root)
        battery = BatteryOptimizationHelper(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the first state is loading and assumes nothing`() = runTest {
        val viewModel = viewModel()

        val initial = viewModel.state.value

        assertTrue(initial.isLoading)
        assertFalse(initial.hasNotificationAccess)
    }

    @Test
    fun `without access the screen says so`() = runTest {
        access.granted = false
        val viewModel = viewModel()

        viewModel.state.test {
            val state = awaitLoaded()
            assertFalse(state.isLoading)
            assertFalse(state.hasNotificationAccess)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh picks up access granted while the user was in settings`() = runTest {
        access.granted = false
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitLoaded().hasNotificationAccess)

            // The system offers no callback for this, which is why the screen re-asks
            // on resume rather than waiting to be told.
            access.granted = true
            viewModel.onEvent(OnboardingEvent.Refresh)

            assertTrue(awaitItem().hasNotificationAccess)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a build that cannot import never offers to`() = runTest {
        importer.isSupported = false
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitLoaded().canImportPastMessages)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `import progress reaches the screen`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertEquals(SmsImportStatus.Idle, awaitLoaded().importStatus)

            importer.status.value = SmsImportStatus.Running(imported = 4, scanned = 120, total = 800)
            assertEquals(
                SmsImportStatus.Running(imported = 4, scanned = 120, total = 800),
                awaitItem().importStatus,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed import is shown rather than left as a spinner`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitLoaded()
            importer.status.value = SmsImportStatus.Failed
            assertEquals(SmsImportStatus.Failed, awaitItem().importStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `starting an import asks the importer, not the worker`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(OnboardingEvent.StartImport)

        assertTrue(importer.started)
    }

    @Test
    fun `a refused permission is remembered until the next try`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitLoaded().importPermissionRefused)

            viewModel.onEvent(OnboardingEvent.ImportPermissionRefused)
            assertTrue(awaitItem().importPermissionRefused)

            viewModel.onEvent(OnboardingEvent.StartImport)
            assertFalse(awaitItem().importPermissionRefused)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a permission granted in settings clears the refusal without importing`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitLoaded()
            viewModel.onEvent(OnboardingEvent.ImportPermissionRefused)
            assertTrue(awaitItem().importPermissionRefused)

            viewModel.onEvent(OnboardingEvent.ImportPermissionGranted)
            assertFalse(awaitItem().importPermissionRefused)
            assertFalse(importer.started)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `finishing records that the explanation has been seen`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(OnboardingEvent.Finish)

        assertTrue(settingsHarness.repository.observeOnboardingCompleted().first())
    }

    @Test
    fun `a stock device is not nagged about battery settings`() = runTest {
        // Robolectric reports no vendor battery screen, which is the stock-Android case.
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitLoaded().showBatteryHint)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The initial value of the state flow is the loading one, and whether the
     * collector sees it depends on how quickly the upstream flows deliver. Skipping
     * it keeps the tests about behaviour rather than about scheduling.
     */
    private suspend fun ReceiveTurbine<OnboardingUiState>.awaitLoaded(): OnboardingUiState {
        var item = awaitItem()
        while (item.isLoading) item = awaitItem()
        return item
    }

    private fun viewModel() = OnboardingViewModel(
        access = access,
        battery = battery,
        importer = importer,
        settings = settingsHarness.repository,
    )

    private class FakeCaptureAccess(var granted: Boolean = false) : CaptureAccess {
        override fun isNotificationAccessGranted(): Boolean = granted
        override fun settingsIntent(): Intent = Intent("test.settings")
        override fun appSettingsIntent(): Intent = Intent("test.app_settings")
        override fun requestRebind() = Unit
    }

    private class FakeSmsImporter(override var isSupported: Boolean = true) : SmsImporter {
        val status = MutableStateFlow<SmsImportStatus>(SmsImportStatus.Idle)
        var started: Boolean = false

        override fun requiredPermissions(): List<String> = listOf("test.permission.READ")
        override fun start() {
            started = true
        }

        override fun observeStatus(): Flow<SmsImportStatus> = status
    }
}
