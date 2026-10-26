package et.alpha.mezgeb.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.demo.DemoModeController
import et.alpha.mezgeb.data.settings.AppLanguage
import et.alpha.mezgeb.data.settings.LanguagePreference
import et.alpha.mezgeb.data.settings.SettingsRepository
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
import java.time.Clock
import javax.inject.Inject

/** Which dialog, if any, is in front of the settings list. */
sealed interface SettingsDialog {
    data object SetPin : SettingsDialog
    data object RemovePin : SettingsDialog
    data object BackupPassphrase : SettingsDialog
    data class RestorePassphrase(val uri: String) : SettingsDialog
    data object EnableDemo : SettingsDialog
    data object DisableDemo : SettingsDialog
}

data class SettingsUiState(
    val isLockEnabled: Boolean = false,
    val isBiometricEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = 60,
    val isDemoMode: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** What the shop calls itself, for the header of an exported summary. */
    val shopName: String = "",
    /** When a backup was last written in full. Null means never. */
    val lastBackupAt: Long? = null,
    /**
     * Set once the database file the app should open has changed. The route restarts
     * the process; the flag lives in state rather than in a one-shot channel so that a
     * restart interrupted by a configuration change still happens.
     */
    val restartRequired: Boolean = false,
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
    data object DemoFailed : SettingsMessage
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

    data class DemoModeToggled(val enabled: Boolean) : SettingsEvent
    data object DemoModeConfirmed : SettingsEvent

    data class LanguageChosen(val language: AppLanguage) : SettingsEvent
    data class ShopNameChanged(val name: String) : SettingsEvent
}

/**
 * Language, the shop name, security, backup and demo mode.
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
    private val demoMode: DemoModeController,
    private val languages: LanguagePreference,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            isLockEnabled = lockRepository.isLockEnabled(),
            isBiometricEnabled = lockRepository.isBiometricEnabled(),
            lockTimeoutSeconds = lockRepository.timeoutSeconds(),
            isDemoMode = demoMode.isEnabled(),
            language = languages.current(),
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Read once rather than collected: the only writer is this screen, and a flow
        // that echoed back what the user just typed would fight the text field.
        viewModelScope.launch {
            val stored = settings.shopName().orEmpty()
            if (stored.isNotEmpty()) _state.update { it.copy(shopName = stored) }
        }
        // Collected, unlike the shop name: nothing on this screen types into it, and
        // the line under Backup should change the moment a backup is written.
        viewModelScope.launch {
            settings.observeLastBackupAt().collect { at ->
                _state.update { it.copy(lastBackupAt = at) }
            }
        }
    }

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

            is SettingsEvent.DemoModeToggled -> _state.update {
                it.copy(
                    dialog = if (event.enabled) {
                        SettingsDialog.EnableDemo
                    } else {
                        SettingsDialog.DisableDemo
                    },
                )
            }

            SettingsEvent.DemoModeConfirmed -> switchDemoMode()

            // Written synchronously, not in a coroutine: the caller recreates the
            // Activity immediately afterwards and has to read back what was just set.
            is SettingsEvent.LanguageChosen -> {
                languages.set(event.language)
                _state.update { it.copy(language = event.language) }
            }

            // Echoed to the field immediately and persisted behind it. A name that
            // lagged a keystroke behind what was typed would be unusable.
            is SettingsEvent.ShopNameChanged -> {
                _state.update { it.copy(shopName = event.name) }
                viewModelScope.launch { settings.setShopName(event.name) }
            }
        }
    }

    /**
     * The real ledger is not copied, exported or restored here. Enabling demo mode
     * points the app at a different file; disabling points it back. Whichever way it
     * goes, the process restarts, because the database is a singleton chosen at startup.
     */
    private fun switchDemoMode() {
        val turningOn = !demoMode.isEnabled()
        _state.update { it.copy(isBusy = true, dialog = null) }

        viewModelScope.launch {
            val result = if (turningOn) demoMode.enable() else runCatching { demoMode.disable() }
            _state.update {
                it.copy(
                    isBusy = false,
                    isDemoMode = demoMode.isEnabled(),
                    restartRequired = result.isSuccess,
                    message = if (result.isSuccess) null else SettingsMessage.DemoFailed,
                )
            }
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
            // Only a whole file counts, and only a file of the real ledger: a backup
            // taken in demo mode holds the demo shop, and stamping it would quiet the
            // reminder for a ledger that was never saved (ROADMAP R23).
            if (result.isSuccess && !demoMode.isEnabled()) {
                settings.recordBackup(clock.millis())
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
