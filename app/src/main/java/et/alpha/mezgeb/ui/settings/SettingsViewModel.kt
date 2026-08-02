package et.alpha.mezgeb.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.domain.backup.BackupRepository
import et.alpha.mezgeb.domain.backup.RestoreException
import et.alpha.mezgeb.domain.backup.RestoreFailure
import et.alpha.mezgeb.domain.lock.AppLockRepository
import et.alpha.mezgeb.domain.lock.AppLockState
import et.alpha.mezgeb.domain.lock.PinCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Which dialog, if any, is in front of the settings list. */
sealed interface SettingsDialog {
    data object SetPin : SettingsDialog
    data object RemovePin : SettingsDialog
    data object BackupPassphrase : SettingsDialog
    data class RestorePassphrase(val uri: String) : SettingsDialog
}

data class SettingsUiState(
    val isLockEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = 60,
    val dialog: SettingsDialog? = null,
    val isBusy: Boolean = false,
    val message: SettingsMessage? = null,
)

/** Something to tell the user once, then forget. */
sealed interface SettingsMessage {
    data object PinSet : SettingsMessage
    data object PinRemoved : SettingsMessage
    data object PinRejected : SettingsMessage
    data class BackupWritten(val uri: String) : SettingsMessage
    data class Restored(val rows: Int) : SettingsMessage
    data class RestoreFailed(val reason: RestoreFailure) : SettingsMessage
    data object BackupFailed : SettingsMessage
}

sealed interface SettingsEvent {
    data object LockRequested : SettingsEvent
    data object UnlockRequested : SettingsEvent
    data class PinEntered(val pin: String, val acknowledged: Boolean) : SettingsEvent
    data object RemovePinConfirmed : SettingsEvent
    data class BiometricToggled(val enabled: Boolean) : SettingsEvent
    data class TimeoutChosen(val seconds: Int) : SettingsEvent

    data object BackupRequested : SettingsEvent
    data class RestoreFileChosen(val uri: String) : SettingsEvent
    data object DialogDismissed : SettingsEvent
    data object MessageShown : SettingsEvent
}

/**
 * Security and backup. Everything else in settings arrives in Session 11.
 *
 * The backup passphrase is never stored — not in DataStore, not in
 * `EncryptedSharedPreferences`, not in this state beyond the moment it is used. A
 * passphrase kept next to the file it protects is decoration.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val lockRepository: AppLockRepository,
    private val lockState: AppLockState,
    private val backups: BackupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            isLockEnabled = lockRepository.isLockEnabled(),
            isBiometricEnabled = lockRepository.isBiometricEnabled(),
            lockTimeoutSeconds = lockRepository.timeoutSeconds(),
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.LockRequested -> _state.update { it.copy(dialog = SettingsDialog.SetPin) }
            SettingsEvent.UnlockRequested ->
                _state.update { it.copy(dialog = SettingsDialog.RemovePin) }

            is SettingsEvent.PinEntered -> setPin(event)
            SettingsEvent.RemovePinConfirmed -> removePin()

            is SettingsEvent.BiometricToggled -> viewModelScope.launch {
                lockRepository.setBiometricEnabled(event.enabled)
                _state.update { it.copy(isBiometricEnabled = event.enabled) }
            }

            is SettingsEvent.TimeoutChosen -> viewModelScope.launch {
                lockRepository.setTimeoutSeconds(event.seconds)
                _state.update { it.copy(lockTimeoutSeconds = event.seconds) }
            }

            SettingsEvent.BackupRequested ->
                _state.update { it.copy(dialog = SettingsDialog.BackupPassphrase) }

            is SettingsEvent.RestoreFileChosen -> _state.update {
                it.copy(dialog = SettingsDialog.RestorePassphrase(event.uri))
            }

            SettingsEvent.DialogDismissed -> _state.update { it.copy(dialog = null) }
            SettingsEvent.MessageShown -> _state.update { it.copy(message = null) }
        }
    }

    /**
     * @param acknowledged the user has read that a lost PIN means lost data. Enforced
     *   here as well as in the dialog, so the rule survives a redesign of the dialog.
     */
    private fun setPin(event: SettingsEvent.PinEntered) {
        if (!event.acknowledged || !PinCredential.isAcceptable(event.pin)) {
            _state.update { it.copy(message = SettingsMessage.PinRejected) }
            return
        }

        viewModelScope.launch {
            val saved = lockRepository.setPin(event.pin)
            if (saved) lockState.onLockEnabled()
            _state.update {
                it.copy(
                    dialog = null,
                    isLockEnabled = saved,
                    message = if (saved) SettingsMessage.PinSet else SettingsMessage.PinRejected,
                )
            }
        }
    }

    private fun removePin() {
        viewModelScope.launch {
            lockRepository.clearPin()
            lockState.onLockDisabled()
            _state.update {
                it.copy(
                    dialog = null,
                    isLockEnabled = false,
                    isBiometricEnabled = false,
                    message = SettingsMessage.PinRemoved,
                )
            }
        }
    }

    fun writeBackup(passphrase: String, uri: String, openStream: () -> OutputStream?) {
        _state.update { it.copy(isBusy = true, dialog = null) }
        viewModelScope.launch {
            val result = runCatching {
                val stream = openStream() ?: error("no output stream")
                stream.use { backups.writeBackup(passphrase.toCharArray(), it) }
            }
            _state.update {
                it.copy(
                    isBusy = false,
                    message = if (result.isSuccess) {
                        SettingsMessage.BackupWritten(uri)
                    } else {
                        SettingsMessage.BackupFailed
                    },
                )
            }
        }
    }

    /**
     * Restore replaces everything. The confirmation lives in the dialog; by the time
     * this runs the user has said yes twice — once to the file, once to the wipe.
     */
    fun restore(passphrase: String, openStream: () -> InputStream?) {
        _state.update { it.copy(isBusy = true, dialog = null) }
        viewModelScope.launch {
            val result = runCatching {
                val stream = openStream() ?: error("no input stream")
                stream.use { backups.restore(it, passphrase.toCharArray()) }
            }.getOrElse { Result.failure(it) }

            _state.update { current ->
                current.copy(
                    isBusy = false,
                    message = result.fold(
                        onSuccess = { SettingsMessage.Restored(it.rowsRestored) },
                        onFailure = { error ->
                            SettingsMessage.RestoreFailed(
                                (error as? RestoreException)?.reason ?: RestoreFailure.MALFORMED,
                            )
                        },
                    ),
                )
            }
        }
    }
}
