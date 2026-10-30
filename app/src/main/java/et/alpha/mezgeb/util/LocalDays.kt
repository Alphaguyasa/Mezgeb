package et.alpha.mezgeb.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.ZoneId

/**
 * The bounds of the current local day, re-emitted when the day changes.
 *
 * An interface so that a screen test can move to tomorrow by setting a value, instead
 * of running a midnight timer on the same test scheduler as everything else.
 */
fun interface LocalDays {
    fun observe(): Flow<LongRange>
}

/**
 * Waits until the next local midnight on the injected clock, then emits the new day.
 *
 * The wait is recomputed from the clock after every wake-up rather than assumed to be
 * a day long: a wake-up that lands a few milliseconds early sees the same day again,
 * which [distinctUntilChanged] drops, and simply waits out the rest. A clock the owner
 * sets by hand is caught by the next refresh on resume, which restarts this flow.
 */
class ClockLocalDays(
    private val clock: Clock,
    private val zone: ZoneId,
) : LocalDays {

    override fun observe(): Flow<LongRange> = flow {
        while (true) {
            val now = clock.millis()
            val today = MezgebDates.dayBounds(now, zone)
            emit(today)
            // `last` is inclusive, so the next day starts one millisecond after it.
            // That is always at least one millisecond from now, so this never spins.
            delay(today.last + 1 - now)
        }
    }.distinctUntilChanged()
}
