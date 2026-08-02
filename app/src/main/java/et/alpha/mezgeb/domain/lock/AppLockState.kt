package et.alpha.mezgeb.domain.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
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
    private val clock: Clock,
) {

    private val _isLocked = MutableStateFlow(repository.isLockEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** When the app was last in the foreground, for the timeout. */
    private var backgroundedAt: Long? = null

    fun onUnlocked() {
        _isLocked.value = false
        backgroundedAt = null
    }

    fun onBackgrounded() {
        if (!_isLocked.value) backgroundedAt = clock.millis()
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
        val elapsed = clock.millis() - since
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
