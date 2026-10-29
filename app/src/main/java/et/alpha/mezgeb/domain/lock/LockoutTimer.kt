package et.alpha.mezgeb.domain.lock

/**
 * Time that the owner of the phone cannot move.
 *
 * The wall clock is a setting: anyone holding the phone can wind it forward past a
 * lockout, or back so a background timeout never runs out. Time since boot only moves
 * forward, and restarts from zero only when the phone does, which [bootCount] tells
 * apart.
 */
interface DeviceUptime {
    /** Milliseconds since this boot, counting deep sleep. */
    fun elapsedMillis(): Long

    /** How many times the phone has started. Changes only on a restart. */
    fun bootCount(): Int
}

/**
 * A PIN lockout as stored: the boot it was started in, when on that boot's clock, and
 * how long it lasts.
 */
data class Lockout(
    val bootCount: Int,
    val startedAtElapsed: Long,
    val durationMillis: Long,
)

/**
 * The arithmetic of a lockout, kept apart from `EncryptedSharedPreferences` so it can
 * be tested without the Keystore.
 */
object LockoutTimer {

    /**
     * [lockout] measured on the clock of the boot the phone is in now.
     *
     * After a restart the old boot's clock means nothing, and the wall clock is exactly
     * what must not be trusted, so the full wait starts again from the restart. That
     * costs an honest owner at most [LockoutPolicy.MAX_DELAY_SECONDS] once, which is
     * the price of a restart not being a way to skip the wait. The same applies if the
     * stored start is somehow ahead of the clock it was read on.
     *
     * A lockout that ran out but was never read before the restart is still on record,
     * so it is served once more. The repository forgets a lockout as soon as it reads
     * it as over, which keeps that case to a phone restarted mid-wait.
     */
    fun anchored(lockout: Lockout, bootCount: Int, elapsedNow: Long): Lockout =
        if (lockout.bootCount == bootCount && lockout.startedAtElapsed <= elapsedNow) {
            lockout
        } else {
            lockout.copy(bootCount = bootCount, startedAtElapsed = elapsedNow)
        }

    /** How long [lockout] still has to run, or zero. Only meaningful once [anchored]. */
    fun remainingMillis(lockout: Lockout, elapsedNow: Long): Long =
        (lockout.durationMillis - (elapsedNow - lockout.startedAtElapsed)).coerceAtLeast(0L)
}
