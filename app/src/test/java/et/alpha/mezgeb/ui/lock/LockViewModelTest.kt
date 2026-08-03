package et.alpha.mezgeb.ui.lock

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.domain.lock.AppLockRepository
import et.alpha.mezgeb.domain.lock.AppLockState
import et.alpha.mezgeb.domain.lock.LockoutPolicy
import et.alpha.mezgeb.domain.lock.PinCredential
import et.alpha.mezgeb.domain.lock.UnlockResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The unlock screen.
 *
 * Most of what is worth testing here is refusal rather than success: this is the one
 * screen whose job is to *not* do something. A keypad that keeps accepting digits while
 * the app is locked out, or that submits a three-digit PIN, is not a cosmetic bug — it
 * is the lockout being decorative.
 */
class LockViewModelTest {

    private val repository: AppLockRepository = mockk(relaxed = true)
    private val lockState: AppLockState = mockk(relaxed = true)
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { repository.lockoutRemainingMillis() } returns 0L
        every { repository.isBiometricEnabled() } returns false
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = LockViewModel(repository, lockState, clock)

    // --- Initial state -------------------------------------------------------------

    @Test
    fun `opens empty, not checking, and unable to submit`() = runTest {
        viewModel().state.test {
            val initial = awaitItem()
            assertEquals("", initial.entered)
            assertFalse(initial.isChecking)
            assertFalse(initial.isLockedOut)
            assertFalse("an empty keypad must not submit", initial.canSubmit)
            assertEquals(LockoutPolicy.FREE_ATTEMPTS, initial.attemptsBeforeLockout)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opens already locked out when the backoff has not expired`() = runTest {
        // The process can be killed and restarted mid-lockout. The waiting has to
        // survive that, or force-stopping the app becomes the way past it.
        every { repository.lockoutRemainingMillis() } returns 30_000L

        viewModel().state.test {
            val initial = awaitItem()
            assertTrue(initial.isLockedOut)
            assertFalse(initial.canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offers biometric only when it is enabled`() = runTest {
        every { repository.isBiometricEnabled() } returns true

        viewModel().state.test {
            assertTrue(awaitItem().biometricAvailable)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Entry ---------------------------------------------------------------------

    @Test
    fun `a PIN becomes submittable only at the minimum length`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()

            repeat(PinCredential.MIN_LENGTH - 1) { index ->
                viewModel.onEvent(LockEvent.DigitPressed(index + 1))
                assertFalse(awaitItem().canSubmit)
            }

            viewModel.onEvent(LockEvent.DigitPressed(9))
            assertTrue(awaitItem().canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the keypad stops accepting digits at the maximum length`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            repeat(PinCredential.MAX_LENGTH) {
                viewModel.onEvent(LockEvent.DigitPressed(1))
                awaitItem()
            }

            // Silently ignored rather than emitting an unchanged state.
            viewModel.onEvent(LockEvent.DigitPressed(2))
            expectNoEvents()
            assertEquals(PinCredential.MAX_LENGTH, viewModel.state.value.entered.length)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a locked-out keypad accepts nothing`() = runTest {
        every { repository.lockoutRemainingMillis() } returns 30_000L
        val viewModel = viewModel()

        viewModel.state.test {
            assertTrue(awaitItem().isLockedOut)

            viewModel.onEvent(LockEvent.DigitPressed(1))

            // The whole point of a backoff: no typing, so no guessing.
            expectNoEvents()
            assertEquals("", viewModel.state.value.entered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Verification --------------------------------------------------------------

    @Test
    fun `the right PIN unlocks and clears what was typed`() = runTest {
        coEvery { repository.verify(any()) } returns UnlockResult.Unlocked
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            enter(viewModel, "1234")
            skipItems(4)

            viewModel.onEvent(LockEvent.Submit)

            val unlocked = awaitItemWhere { !it.isChecking }
            assertEquals("", unlocked.entered)
            assertEquals(0, unlocked.wrongAttempts)
            cancelAndIgnoreRemainingEvents()
        }

        verify { lockState.onUnlocked() }
    }

    @Test
    fun `a wrong PIN clears the entry and counts the attempt`() = runTest {
        coEvery { repository.verify(any()) } returns UnlockResult.Wrong(failedAttempts = 2)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            enter(viewModel, "1234")
            skipItems(4)

            viewModel.onEvent(LockEvent.Submit)

            val wrong = awaitItemWhere { !it.isChecking && it.wrongAttempts > 0 }
            assertEquals("", wrong.entered)
            assertEquals(2, wrong.wrongAttempts)
            assertEquals(LockoutPolicy.FREE_ATTEMPTS - 2, wrong.attemptsBeforeLockout)
            // The marker shows once the field is empty again, not over a half-typed PIN.
            assertTrue(wrong.showWrong)
            cancelAndIgnoreRemainingEvents()
        }

        verify(exactly = 0) { lockState.onUnlocked() }
    }

    @Test
    fun `too many wrong PINs starts the waiting, measured from now`() = runTest {
        coEvery { repository.verify(any()) } returns
            UnlockResult.LockedOut(retryAtMillis = NOW + 45_000L)
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            enter(viewModel, "1234")
            skipItems(4)

            viewModel.onEvent(LockEvent.Submit)

            val out = awaitItemWhere { it.isLockedOut }
            // A remaining time, not an absolute instant — the screen counts down.
            assertEquals(45_000L, out.lockedOutForMillis)
            assertEquals("", out.entered)
            assertFalse(out.canSubmit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a lockout that expired while the screen sat open reports zero, never negative`() =
        runTest {
            // retryAt in the past. Left unclamped this is a negative duration, and
            // isLockedOut reads `> 0`, so the screen would look unlocked while every
            // countdown label rendered a negative number.
            coEvery { repository.verify(any()) } returns
                UnlockResult.LockedOut(retryAtMillis = NOW - 5_000L)
            val viewModel = viewModel()

            viewModel.state.test {
                awaitItem()
                enter(viewModel, "1234")
                skipItems(4)

                viewModel.onEvent(LockEvent.Submit)

                val settled = awaitItemWhere { !it.isChecking }
                assertEquals(0L, settled.lockedOutForMillis)
                assertFalse(settled.isLockedOut)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `submitting a too-short PIN does not reach the repository`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            enter(viewModel, "12")
            skipItems(2)

            viewModel.onEvent(LockEvent.Submit)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        // Not merely rejected in the UI: never handed to the hash comparison at all.
        coVerify(exactly = 0) { repository.verify(any()) }
    }

    @Test
    fun `refresh re-reads the remaining lockout`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            assertFalse(awaitItem().isLockedOut)

            // The lockout can begin on another screen, or expire while this one waits.
            every { repository.lockoutRemainingMillis() } returns 10_000L
            viewModel.onEvent(LockEvent.Refresh)

            assertEquals(10_000L, awaitItem().lockedOutForMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a successful biometric unlocks without ever seeing a PIN`() = runTest {
        val viewModel = viewModel()

        viewModel.onEvent(LockEvent.BiometricSucceeded)

        verify { lockState.onUnlocked() }
        coVerify(exactly = 0) { repository.verify(any()) }
    }

    private fun enter(viewModel: LockViewModel, pin: String) {
        pin.forEach { viewModel.onEvent(LockEvent.DigitPressed(it.digitToInt())) }
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    private companion object {
        const val NOW = 1_782_950_400_000L
    }
}
