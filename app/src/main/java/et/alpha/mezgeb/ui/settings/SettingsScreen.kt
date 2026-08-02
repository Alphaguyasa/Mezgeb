package et.alpha.mezgeb.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.backup.RestoreFailure
import et.alpha.mezgeb.domain.lock.PinCredential

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPassphrase by remember { mutableStateOf("") }

    val createBackup = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri != null) {
            viewModel.writeBackup(pendingPassphrase, uri.toString()) {
                context.contentResolver.openOutputStream(uri)
            }
        }
        pendingPassphrase = ""
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.onEvent(SettingsEvent.RestoreFileChosen(uri.toString()))
    }

    SettingsScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onBackupConfirmed = { passphrase ->
            pendingPassphrase = passphrase
            createBackup.launch(BACKUP_FILE_NAME)
        },
        onRestoreRequested = { openBackup.launch(arrayOf("*/*")) },
        onRestoreConfirmed = { uri, passphrase ->
            viewModel.restore(passphrase) {
                context.contentResolver.openInputStream(Uri.parse(uri))
            }
        },
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    onBackupConfirmed: (String) -> Unit,
    onRestoreRequested: () -> Unit,
    onRestoreConfirmed: (uri: String, passphrase: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }

    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            onEvent(SettingsEvent.MessageShown)
        }
    }

    MezgebScaffold(
        title = stringResource(R.string.destination_settings),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
        snackbarHostState = snackbarHostState,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = stringResource(R.string.settings_security),
                style = MaterialTheme.typography.titleSmall,
            )

            SwitchRow(
                label = stringResource(R.string.settings_lock),
                supporting = stringResource(R.string.settings_lock_body),
                checked = state.isLockEnabled,
                onCheckedChange = {
                    onEvent(
                        if (it) SettingsEvent.LockRequested else SettingsEvent.UnlockRequested,
                    )
                },
            )

            if (state.isLockEnabled) {
                SwitchRow(
                    label = stringResource(R.string.settings_biometric),
                    supporting = stringResource(R.string.settings_biometric_body),
                    checked = state.isBiometricEnabled,
                    onCheckedChange = { onEvent(SettingsEvent.BiometricToggled(it)) },
                )

                Text(
                    text = stringResource(R.string.settings_timeout),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    listOf(0, 60, 300).forEach { seconds ->
                        FilterChip(
                            selected = state.lockTimeoutSeconds == seconds,
                            onClick = { onEvent(SettingsEvent.TimeoutChosen(seconds)) },
                            label = { Text(timeoutLabel(seconds)) },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = spacing.sm))

            Text(
                text = stringResource(R.string.settings_backup),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_backup_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { onEvent(SettingsEvent.BackupRequested) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.settings_backup_action))
            }
            OutlinedButton(
                onClick = onRestoreRequested,
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.settings_restore_action))
            }
        }
    }

    when (val dialog = state.dialog) {
        SettingsDialog.SetPin -> SetPinDialog(
            onDismiss = { onEvent(SettingsEvent.DialogDismissed) },
            onConfirm = { pin, acknowledged ->
                onEvent(SettingsEvent.PinEntered(pin, acknowledged))
            },
        )

        SettingsDialog.RemovePin -> ConfirmDialog(
            title = stringResource(R.string.settings_lock_remove_title),
            body = stringResource(R.string.settings_lock_remove_body),
            confirmLabel = stringResource(R.string.settings_lock_remove_confirm),
            onConfirm = { onEvent(SettingsEvent.RemovePinConfirmed) },
            onDismiss = { onEvent(SettingsEvent.DialogDismissed) },
        )

        SettingsDialog.BackupPassphrase -> PassphraseDialog(
            title = stringResource(R.string.settings_backup_action),
            body = stringResource(R.string.settings_backup_passphrase_body),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = onBackupConfirmed,
            onDismiss = { onEvent(SettingsEvent.DialogDismissed) },
        )

        is SettingsDialog.RestorePassphrase -> PassphraseDialog(
            title = stringResource(R.string.settings_restore_action),
            // The warning is on the passphrase dialog rather than a separate step: it
            // is the last moment before a ledger is replaced, and it is where someone
            // is actually looking.
            body = stringResource(R.string.settings_restore_warning),
            confirmLabel = stringResource(R.string.settings_restore_confirm),
            onConfirm = { onRestoreConfirmed(dialog.uri, it) },
            onDismiss = { onEvent(SettingsEvent.DialogDismissed) },
        )

        null -> Unit
    }
}

/**
 * PIN setup, with the acknowledgement the session brief requires.
 *
 * The confirm button stays disabled until the box is ticked. "The data is lost" is not
 * a footnote here — it is the reason there is a checkbox at all, and a shop owner who
 * skims it and loses a year of takings has been failed by this dialog.
 */
@Composable
private fun SetPinDialog(onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_lock_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MezgebTheme.spacing.md)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { entry ->
                        pin = entry.filter { it.isDigit() }.take(PinCredential.MAX_LENGTH)
                    },
                    label = { Text(stringResource(R.string.settings_lock_pin_label)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.settings_lock_no_recovery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text(
                        text = stringResource(R.string.settings_lock_acknowledge),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin, acknowledged) },
                enabled = acknowledged && PinCredential.isAcceptable(pin),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MezgebTheme.spacing.md)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.settings_passphrase_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.length >= MIN_PASSPHRASE_LENGTH,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MezgebTheme.spacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun messageText(message: SettingsMessage): String = when (message) {
    SettingsMessage.PinSet -> stringResource(R.string.settings_message_pin_set)
    SettingsMessage.PinRemoved -> stringResource(R.string.settings_message_pin_removed)
    SettingsMessage.PinRejected -> stringResource(R.string.settings_message_pin_rejected)
    is SettingsMessage.BackupWritten -> stringResource(R.string.settings_message_backup_written)
    is SettingsMessage.Restored -> stringResource(R.string.settings_message_restored, message.rows)
    SettingsMessage.BackupFailed -> stringResource(R.string.settings_message_backup_failed)
    is SettingsMessage.RestoreFailed -> stringResource(
        when (message.reason) {
            RestoreFailure.NOT_A_BACKUP -> R.string.settings_restore_not_a_backup
            RestoreFailure.UNSUPPORTED_FORMAT,
            RestoreFailure.UNSUPPORTED_SCHEMA,
            -> R.string.settings_restore_too_new
            RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT -> R.string.settings_restore_wrong_passphrase
            RestoreFailure.MALFORMED -> R.string.settings_restore_malformed
        },
    )
}

@Composable
private fun timeoutLabel(seconds: Int): String = when (seconds) {
    0 -> stringResource(R.string.settings_timeout_immediately)
    else -> stringResource(R.string.settings_timeout_seconds, seconds)
}

/** `ACTION_CREATE_DOCUMENT` with a fixed type; the backup is only ever one thing. */
private class CreateBackupDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(BACKUP_MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == Activity.RESULT_OK }?.data
}

private const val BACKUP_FILE_NAME = "mezgeb-backup.mzgb"
private const val BACKUP_MIME_TYPE = "application/octet-stream"

/** Short enough to type on a phone, long enough that PBKDF2 has something to work with. */
private const val MIN_PASSPHRASE_LENGTH = 8

@Preview(name = "Settings", showBackground = true, heightDp = 800)
@Composable
private fun SettingsPreview() {
    MezgebTheme {
        SettingsScreen(
            state = SettingsUiState(isLockEnabled = true, isBiometricEnabled = true),
            onEvent = {},
            onBack = {},
            onBackupConfirmed = {},
            onRestoreRequested = {},
            onRestoreConfirmed = { _, _ -> },
        )
    }
}
