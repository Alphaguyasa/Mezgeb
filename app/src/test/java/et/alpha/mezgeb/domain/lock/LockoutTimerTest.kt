package et.alpha.mezgeb.domain.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lockout is timed on uptime, so the wall clock is not an input at all: winding
 * the phone's time forward or back cannot change any answer here. These tests are
 * about what can still happen to uptime, which is a restart.
 */
class LockoutTimerTest {

    private val thirtySeconds = Lockout(bootCount = 7, startedAtElapsed = 100_000L, durationMillis = 30_000L)

    @Test
    fun `a lockout runs for its full length on the boot it started in`() {
        val lockout = LockoutTimer.anchored(thirtySeconds, bootCount = 7, elapsedNow = 110_000L)

        assertEquals(thirtySeconds, lockout)
        assertEquals(20_000L, LockoutTimer.remainingMillis(lockout, elapsedNow = 110_000L))
        assertEquals(0L, LockoutTimer.remainingMillis(lockout, elapsedNow = 130_000L))
        assertEquals(0L, LockoutTimer.remainingMillis(lockout, elapsedNow = 500_000L))
    }

    @Test
    fun `a restart starts the wait again rather than ending it`() {
        // A new boot whose uptime is already past where the old one started: the
        // subtraction alone would call the lockout over.
        val lockout = LockoutTimer.anchored(thirtySeconds, bootCount = 8, elapsedNow = 400_000L)

        assertEquals(Lockout(8, 400_000L, 30_000L), lockout)
        assertEquals(30_000L, LockoutTimer.remainingMillis(lockout, elapsedNow = 400_000L))
    }

    @Test
    fun `a start ahead of the clock is treated as a restart`() {
        // No boot count on this ROM, and a restart shortly after the lockout began.
        val noBootCount = thirtySeconds.copy(bootCount = 0)
        val lockout = LockoutTimer.anchored(noBootCount, bootCount = 0, elapsedNow = 5_000L)

        assertEquals(Lockout(0, 5_000L, 30_000L), lockout)
        assertEquals(30_000L, LockoutTimer.remainingMillis(lockout, elapsedNow = 5_000L))
    }

    @Test
    fun `the remaining time never goes negative`() {
        assertEquals(0L, LockoutTimer.remainingMillis(thirtySeconds, elapsedNow = Long.MAX_VALUE / 2))
    }
}
