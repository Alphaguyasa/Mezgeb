package et.alpha.mezgeb

import app.cash.turbine.test
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.navigation.MezgebDestination
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var settingsHarness: SettingsHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsHarness = SettingsHarness(tempFolder.root)
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is decided before the flag has been read`() {
        // The initial value exists only so the activity can draw nothing rather than
        // draw Today and then replace it.
        assertFalse(MainUiState().isResolved)
        assertEquals(MezgebDestination.Today, MainUiState().startDestination)
    }

    @Test
    fun `a shop that has not seen the explanation gets it first`() = runTest {
        MainViewModel(settingsHarness.repository).state.test {
            val resolved = awaitResolved()
            assertEquals(MezgebDestination.Onboarding, resolved.startDestination)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `once onboarding is done the app opens on Today`() = runTest {
        settingsHarness.repository.setOnboardingCompleted(true)

        MainViewModel(settingsHarness.repository).state.test {
            assertEquals(MezgebDestination.Today, awaitResolved().startDestination)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the start destination is resolved once and does not move again`() = runTest {
        val viewModel = MainViewModel(settingsHarness.repository)

        viewModel.state.test {
            assertEquals(MezgebDestination.Onboarding, awaitResolved().startDestination)

            // Completing onboarding navigates. If it also re-resolved the start
            // destination, the graph would be rebuilt and the back stack thrown away.
            settingsHarness.repository.setOnboardingCompleted(true)
            expectNoEvents()
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<MainUiState>.awaitResolved(): MainUiState {
        var item = awaitItem()
        while (!item.isResolved) item = awaitItem()
        assertTrue(item.isResolved)
        return item
    }
}
