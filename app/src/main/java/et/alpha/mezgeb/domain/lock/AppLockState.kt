package et.alpha.mezgeb.domain.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app is currently locked, held for the process rather than a screen.
 *
 * It is a singleton because the answer must survive the Activity: rotating the phone,
 * or Android quietly recreating the task, must not be a way past the lock. Cold start
 * begins locked by construction — the only thing that ever sets it to unlocked is a
 * verified PIN or a biometric match.
 */
@Singleton
class AppLockState @Inject constructor(
    private val repository: AppLockRepository,
    private val uptime: DeviceUptime,
) {

    private val _isLocked = MutableStateFlow(repository.isLockEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * When the app was last in the foreground, for the timeout, on [DeviceUptime].
     *
     * Not the wall clock: setting the phone's time back would make the time away
     * negative, and the app would never lock again. A restart ends the process, and a
     * new process starts locked, so time since boot is all this needs.
     */
    private var backgroundedAt: Long? = null

    fun onUnlocked() {
        _isLocked.value = false
        backgroundedAt = null
    }

    /**
     * [changingConfigurations] is the Activity's `isChangingConfigurations`. Turning the
     * phone or switching the language destroys and recreates the Activity, so it stops
     * and starts again within milliseconds without the app ever leaving the screen.
     * Recording that as time away would lock "Lock immediately" on every rotation,
     * because zero milliseconds away meets a zero timeout. The app is still in front of
     * the same person, so nothing is recorded.
     */
    fun onBackgrounded(changingConfigurations: Boolean = false) {
        if (changingConfigurations) return
        if (!_isLocked.value) backgroundedAt = uptime.elapsedMillis()
    }

    /**
     * Re-locks if the app has been away longer than the timeout.
     *
     * A timeout of zero locks the moment the app leaves the foreground, which is what
     * someone who handed their phone to a customer would want.
     */
    fun onForegrounded() {
        if (!repository.isLockEnabled()) {
            _isLocked.value = false
            return
        }
        val since = backgroundedAt ?: return
        val elapsed = uptime.elapsedMillis() - since
        if (elapsed >= repository.timeoutSeconds() * 1000L) {
            _isLocked.value = true
        }
        backgroundedAt = null
    }

    /** Called when the lock is turned off, so the current session is not left stuck. */
    fun onLockDisabled() {
        _isLocked.value = false
        backgroundedAt = null
    }

    /** Called when a lock is first set: the shop is already looking at the app. */
    fun onLockEnabled() {
        _isLocked.value = false
    }
}
