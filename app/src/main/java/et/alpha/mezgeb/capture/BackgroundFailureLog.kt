package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts background work that failed, so a failure is seen instead of crashing the app.
 *
 * The notification listener hands every message to the application scope. Before this
 * existed, an exception there (most likely a write refused because a cheap phone's
 * storage is full) went to the thread's default handler and killed the process, and it
 * did so again on the next notification. [handler] is installed on that scope, so the
 * process survives and the Today screen says that something could not be saved.
 *
 * What is recorded is a count and a time, nothing else. The exception is not logged
 * and not stored: SQLite puts the values it refused into its messages, and a stack
 * trace in a log is exactly where a phone number or an amount must never end up.
 */
@Singleton
class BackgroundFailureLog @Inject constructor(
    private val settings: SettingsRepository,
    private val clock: Clock,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) {

    /**
     * Its own scope, not the application scope: the handler must not depend on the
     * scope it is installed on. Every write is wrapped, so nothing can escape from here
     * either; when storage is full the stamp is lost too, and that is acceptable.
     */
    private val writer = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Swallows the failure after counting it. Errors such as running out of memory are
     * counted and swallowed as well: crashing on every incoming notification would not
     * free any memory, and it would stop capture altogether.
     */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> record() }

    /** Counts one failure, stamped now. Safe to call from any thread. */
    fun record() {
        val at = clock.millis()
        writer.launch { runCatching { settings.recordBackgroundFailure(at) } }
    }
}
