package et.alpha.mezgeb.domain.lock

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-lock, timed on uptime. `AppLockRepository` needs the Keystore, so it is
 * mocked; only its two answers matter here.
 */
class AppLockStateTest {

    private val uptime = FakeUptime()
    private val repository: AppLockRepository = mockk {
        every { isLockEnabled() } returns true
        every { timeoutSeconds() } returns 60
    }
    private val lockState = AppLockState(repository, uptime)

    @Test
    fun `away for longer than the timeout locks`() {
        lockState.onUnlocked()
        lockState.onBackgrounded()
        uptime.elapsed += 61_000L
        lockState.onForegrounded()

        assertTrue(lockState.isLocked.value)
    }

    @Test
    fun `away for less than the timeout does not`() {
        lockState.onUnlocked()
        lockState.onBackgrounded()
        uptime.elapsed += 59_000L
        lockState.onForegrounded()

        assertFalse(lockState.isLocked.value)
    }

    @Test
    fun `setting the phone's clock back does not stop the app locking`() {
        // The wall clock is not an input any more: whatever the phone's time says,
        // uptime moved forward by two minutes, and that is what counts. Measured on
        // the wall clock, a clock set back an hour gave a negative time away and the
        // app never locked again.
        lockState.onUnlocked()
        lockState.onBackgrounded()
        uptime.elapsed += 120_000L
        lockState.onForegrounded()

        assertTrue(lockState.isLocked.value)
    }

    @Test
    fun `a zero timeout locks as soon as the app comes back`() {
        every { repository.timeoutSeconds() } returns 0
        lockState.onUnlocked()
        lockState.onBackgrounded()
        lockState.onForegrounded()

        assertTrue(lockState.isLocked.value)
    }

    @Test
    fun `a zero timeout does not lock on rotation or a language change`() {
        // MainActivity passes isChangingConfigurations: the Activity is recreated, but
        // the app never left the screen.
        every { repository.timeoutSeconds() } returns 0
        lockState.onUnlocked()
        lockState.onBackgrounded(changingConfigurations = true)
        lockState.onForegrounded()

        assertFalse(lockState.isLocked.value)
    }

    @Test
    fun `a rotation does not hide a real absence that follows it`() {
        // Rotate, then leave for two minutes: the real stop is the one that counts.
        lockState.onUnlocked()
        lockState.onBackgrounded(changingConfigurations = true)
        lockState.onForegrounded()
        lockState.onBackgrounded()
        uptime.elapsed += 120_000L
        lockState.onForegrounded()

        assertTrue(lockState.isLocked.value)
    }

    @Test
    fun `a rotation while away does not reset the time away`() {
        // Leaving, then a configuration change while stopped (the language changed in
        // system settings), must not shorten the measured absence.
        lockState.onUnlocked()
        lockState.onBackgrounded()
        uptime.elapsed += 120_000L
        lockState.onBackgrounded(changingConfigurations = true)
        lockState.onForegrounded()

        assertTrue(lockState.isLocked.value)
    }

    private class FakeUptime : DeviceUptime {
        var elapsed = 1_000_000L
        override fun elapsedMillis() = elapsed
        override fun bootCount() = 1
    }
}
