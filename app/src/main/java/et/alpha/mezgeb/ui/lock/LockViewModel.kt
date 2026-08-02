package et.alpha.mezgeb.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.domain.lock.AppLockRepository
import et.alpha.mezgeb.domain.lock.AppLockState
import et.alpha.mezgeb.domain.lock.LockoutPolicy
import et.alpha.mezgeb.domain.lock.PinCredential
import et.alpha.mezgeb.domain.lock.UnlockResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class LockUiState(
    val entered: String = "",
    val isChecking: Boolean = false,
    val wrongAttempts: Int = 0,
    /** Non-zero while the app is refusing to look at another PIN. */
    val lockedOutForMillis: Long = 0L,
    val biometricAvailable: Boolean = false,
) {
    val isLockedOut: Boolean get() = lockedOutForMillis > 0
    val canSubmit: Boolean get() = PinCredential.isAcceptable(entered) && !isChecking && !isLockedOut
    val showWrong: Boolean get() = wrongAttempts > 0 && entered.isEmpty()

    /** How many tries remain before the waiting starts. */
    val attemptsBeforeLockout: Int
        get() = (LockoutPolicy.FREE_ATTEMPTS - wrongAttempts).coerceAtLeast(0)
}

sealed interface LockEvent {
    data class DigitPressed(val digit: Int) : LockEvent
    data object BackspacePressed : LockEvent
    data object Submit : LockEvent
    data object BiometricSucceeded : LockEvent
    data object Refresh : LockEvent
}

/**
 * The unlock screen.
 *
 * There is no "forgot my PIN". The data is on the device and nothing else holds a key
 * to it, so a recovery path would have to be a back door — and a back door in a
 * shop's ledger is worth more to a thief than the phone is. The setup screen says so
 * in plain words and makes the user acknowledge it; this screen offers no way out
 * because there is not one.
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val repository: AppLockRepository,
    private val lockState: AppLockState,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LockUiState(
            lockedOutForMillis = repository.lockoutRemainingMillis(),
            biometricAvailable = repository.isBiometricEnabled(),
        ),
    )
    val state: StateFlow<LockUiState> = _state.asStateFlow()

    fun onEvent(event: LockEvent) {
        when (event) {
            is LockEvent.DigitPressed -> _state.update { current ->
                if (current.entered.length >= PinCredential.MAX_LENGTH || current.isLockedOut) {
                    current
                } else {
                    // Clearing the wrong-PIN marker on the first new digit, so the
                    // message does not sit there accusing someone mid-retry.
                    current.copy(entered = current.entered + event.digit)
                }
            }

            LockEvent.BackspacePressed -> _state.update {
                it.copy(entered = it.entered.dropLast(1))
            }

            LockEvent.Submit -> submit()

            LockEvent.BiometricSucceeded -> lockState.onUnlocked()

            LockEvent.Refresh -> _state.update {
                it.copy(lockedOutForMillis = repository.lockoutRemainingMillis())
            }
        }
    }

    private fun submit() {
        val pin = _state.value.entered
        if (!PinCredential.isAcceptable(pin)) return

        _state.update { it.copy(isChecking = true) }
        viewModelScope.launch {
            when (val result = repository.verify(pin)) {
                UnlockResult.Unlocked -> {
                    _state.update { LockUiState(biometricAvailable = it.biometricAvailable) }
                    lockState.onUnlocked()
                }

                is UnlockResult.Wrong -> _state.update {
                    it.copy(entered = "", isChecking = false, wrongAttempts = result.failedAttempts)
                }

                is UnlockResult.LockedOut -> _state.update {
                    it.copy(
                        entered = "",
                        isChecking = false,
                        lockedOutForMillis = (result.retryAtMillis - clock.millis())
                            .coerceAtLeast(0L),
                    )
                }
            }
        }
    }
}
