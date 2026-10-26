package et.alpha.mezgeb.ui.settings

import app.cash.turbine.test
import et.alpha.mezgeb.data.demo.DemoModeController
import et.alpha.mezgeb.data.settings.AppLanguage
import et.alpha.mezgeb.data.settings.LanguagePreference
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.backup.BackupRepository
import et.alpha.mezgeb.domain.lock.AppLockRepository
import et.alpha.mezgeb.domain.lock.AppLockState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Settings, with demo mode as the part worth the most attention: it is the only switch
 * in the app that decides which database file a shop's data is read from, and getting
 * it wrong in the wrong direction means writing demo rows into a real ledger.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val lockRepository: AppLockRepository = mockk(relaxed = true)
    private val lockState: AppLockState = mockk(relaxed = true)
    private val backups: BackupRepository = mockk(relaxed = true)
    private val demoMode: DemoModeController = mockk(relaxed = true)
    private val languages: LanguagePreference = mockk(relaxed = true)
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val lastBackupAt = MutableStateFlow<Long?>(null)

    private val now = Instant.parse("2026-10-12T09:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { lockRepository.isLockEnabled() } returns false
        every { lockRepository.isBiometricEnabled() } returns false
        every { lockRepository.timeoutSeconds() } returns 60
        every { demoMode.isEnabled() } returns false
        every { languages.current() } returns AppLanguage.SYSTEM
        every { settings.observeLastBackupAt() } returns lastBackupAt
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        SettingsViewModel(lockRepository, lockState, backups, demoMode, languages, settings, clock)

    // --- Initial state -----------------------------------------------------------------

    @Test
    fun `initial state reflects the stored lock and demo settings`() = runTest {
        every { lockRepository.isLockEnabled() } returns true
        every { lockRepository.timeoutSeconds() } returns 300
        every { demoMode.isEnabled() } returns true

        viewModel().state.test {
            val state = awaitItem()
            assertTrue(state.isLockEnabled)
            assertEquals(300, state.lockTimeoutSeconds)
            assertTrue(state.isDemoMode)
            assertFalse(state.restartRequired)
            assertNull(state.dialog)
        }
    }

    // --- Demo mode ---------------------------------------------------------------------

    @Test
    fun `toggling demo on asks first and changes nothing yet`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(SettingsEvent.DemoModeToggled(enabled = true))
            assertEquals(SettingsDialog.EnableDemo, awaitItem().dialog)
        }

        coVerify(exactly = 0) { demoMode.enable() }
        coVerify(exactly = 0) { demoMode.disable() }
    }

    @Test
    fun `toggling demo off asks with the other dialog`() = runTest {
        every { demoMode.isEnabled() } returns true
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(SettingsEvent.DemoModeToggled(enabled = false))
            assertEquals(SettingsDialog.DisableDemo, awaitItem().dialog)
        }

        coVerify(exactly = 0) { demoMode.disable() }
    }

    @Test
    fun `dismissing the dialog leaves demo mode alone`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.DemoModeToggled(enabled = true))
        viewModel.onEvent(SettingsEvent.DialogDismissed)

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.dialog)
            assertFalse(state.isDemoMode)
            assertFalse(state.restartRequired)
        }
        coVerify(exactly = 0) { demoMode.enable() }
    }

    @Test
    fun `confirming enables the demo and asks for a restart`() = runTest {
        coEvery { demoMode.enable() } answers {
            every { demoMode.isEnabled() } returns true
            Result.success(Unit)
        }

        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.DemoModeToggled(enabled = true))
        viewModel.onEvent(SettingsEvent.DemoModeConfirmed)

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isDemoMode)
            assertTrue(state.restartRequired)
            assertFalse(state.isBusy)
            assertNull(state.dialog)
            assertNull(state.message)
        }
        coVerify(exactly = 1) { demoMode.enable() }
    }

    @Test
    fun `confirming from demo mode disables it rather than enabling it again`() = runTest {
        every { demoMode.isEnabled() } returns true
        coEvery { demoMode.disable() } answers {
            every { demoMode.isEnabled() } returns false
        }

        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.DemoModeToggled(enabled = false))
        viewModel.onEvent(SettingsEvent.DemoModeConfirmed)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isDemoMode)
            assertTrue(state.restartRequired)
        }
        coVerify(exactly = 1) { demoMode.disable() }
        coVerify(exactly = 0) { demoMode.enable() }
    }

    /**
     * The failure that matters: seeding threw, so the flag was never flipped. The app
     * must stay on the real ledger and must not restart into a half-built demo.
     */
    @Test
    fun `a failed demo leaves the app on the real ledger and does not restart`() = runTest {
        coEvery { demoMode.enable() } returns Result.failure(IllegalStateException("disk full"))

        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.DemoModeToggled(enabled = true))
        viewModel.onEvent(SettingsEvent.DemoModeConfirmed)

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isDemoMode)
            assertFalse(state.restartRequired)
            assertEquals(SettingsMessage.DemoFailed, state.message)
            assertFalse(state.isBusy)
        }
    }

    // --- Language ----------------------------------------------------------------------

    @Test
    fun `the language starts at whatever is stored`() = runTest {
        every { languages.current() } returns AppLanguage.AMHARIC

        viewModel().state.test {
            assertEquals(AppLanguage.AMHARIC, awaitItem().language)
        }
    }

    /**
     * Written through before the state updates, because the caller recreates the
     * Activity on the next line and the new one reads the store, not this state.
     */
    @Test
    fun `choosing a language writes it through immediately`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.LanguageChosen(AppLanguage.AMHARIC))

        verify(exactly = 1) { languages.set(AppLanguage.AMHARIC) }
        viewModel.state.test {
            assertEquals(AppLanguage.AMHARIC, awaitItem().language)
        }
    }

    // --- Last backup (ROADMAP R23) -------------------------------------------------------

    @Test
    fun `the last backup starts as never and follows the store`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertNull(awaitItem().lastBackupAt)
            lastBackupAt.value = now - 1_000
            assertEquals(now - 1_000, awaitItem().lastBackupAt)
        }
    }

    @Test
    fun `a backup written in full is stamped with the time`() = runTest {
        viewModel().writeBackup("correct horse", "content://backup") { ByteArrayOutputStream() }

        coVerify(exactly = 1) { settings.recordBackup(now) }
    }

    @Test
    fun `a failed backup is not stamped`() = runTest {
        coEvery { backups.writeBackup(any(), any()) } throws IOException("disk full")

        val viewModel = viewModel()
        viewModel.writeBackup("correct horse", "content://backup") { ByteArrayOutputStream() }

        assertEquals(SettingsMessage.BackupFailed, viewModel.state.value.message)
        coVerify(exactly = 0) { settings.recordBackup(any()) }
    }

    @Test
    fun `a backup with no file to write to is not stamped`() = runTest {
        viewModel().writeBackup("correct horse", "content://backup") { null }

        coVerify(exactly = 0) { settings.recordBackup(any()) }
    }

    /** A demo backup holds the demo shop. Stamping it would quiet the real reminder. */
    @Test
    fun `a backup taken in demo mode is not stamped`() = runTest {
        every { demoMode.isEnabled() } returns true

        viewModel().writeBackup("correct horse", "content://backup") { ByteArrayOutputStream() }

        coVerify(exactly = 0) { settings.recordBackup(any()) }
    }

    // --- PIN, which shares the same dialog machinery -----------------------------------

    @Test
    fun `a PIN offered without the acknowledgement is rejected`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.PinEntered("1379", acknowledged = false))

        viewModel.state.test {
            assertEquals(SettingsMessage.PinRejected, awaitItem().message)
        }
    }

    @Test
    fun `an unacceptable PIN is rejected even when acknowledged`() = runTest {
        val viewModel = viewModel()
        viewModel.onEvent(SettingsEvent.PinEntered("1111", acknowledged = true))

        viewModel.state.test {
            assertEquals(SettingsMessage.PinRejected, awaitItem().message)
        }
    }
}
