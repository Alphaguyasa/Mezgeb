package et.alpha.mezgeb.capture

import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureHealth] as a flow, for the Today screen and the onboarding screen.
 *
 * Notification access cannot be observed — the system exposes no callback for it
 * being revoked in settings — so the state is recomputed on [refresh], which the UI
 * calls when it comes back to the foreground. That is the moment it matters: the
 * user has just been to settings and come back.
 */
@Singleton
class CaptureHealthMonitor @Inject constructor(
    private val settings: SettingsRepository,
    private val access: CaptureAccess,
    private val clock: Clock,
) {

    private val refreshes = MutableStateFlow(0)

    fun refresh() {
        refreshes.update { it + 1 }
    }

    fun observe(): Flow<CaptureHealth> = combine(
        settings.observeLastCaptureAt(),
        settings.observeFirstRunAt(),
        refreshes,
    ) { lastCaptureAt, firstRunAt, _ ->
        val now = clock.millis()
        CaptureHealth.evaluate(
            hasNotificationAccess = access.isNotificationAccessGranted(),
            lastCaptureAt = lastCaptureAt,
            since = firstRunAt ?: now,
            now = now,
        )
    }
}
