package et.alpha.mezgeb.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.sms.SmsImportStatus
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.MezgebScaffold

/**
 * The screen that asks for notification access.
 *
 * This is the only place the app explains itself before it starts reading anything,
 * so the copy is the feature. It says what is read, what is never done, and how to
 * turn it off — in that order, before the button, in both languages.
 */
@Composable
fun OnboardingRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Notification access is granted in system settings, in another app. Coming back
    // to this screen is the only signal that anything might have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(OnboardingEvent.Refresh)
    }

    val importPermissions = remember { viewModel.importPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // The progress notification is a nicety; reading the messages is the point.
        // Only the importer's own permissions decide whether the import can run.
        if (importPermissions.all { results[it] == true }) {
            viewModel.onEvent(OnboardingEvent.StartImport)
        }
    }

    OnboardingScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenNotificationSettings = {
            context.startSafely(viewModel.notificationSettingsIntent())
        },
        onOpenBatterySettings = {
            viewModel.onEvent(OnboardingEvent.BatterySettingsOpened)
            context.startSafely(viewModel.batterySettingsIntent())
        },
        onStartImport = {
            if (importPermissions.isEmpty()) {
                viewModel.onEvent(OnboardingEvent.StartImport)
            } else {
                permissionLauncher.launch(withNotificationPermission(importPermissions))
            }
        },
        onFinish = {
            viewModel.onEvent(OnboardingEvent.Finish)
            onFinished()
        },
        modifier = modifier,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onStartImport: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    MezgebScaffold(
        title = stringResource(R.string.onboarding_title),
        modifier = modifier,
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenGutter, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(R.string.onboarding_promise_heading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Promise(stringResource(R.string.onboarding_promise_no_payments))
                Promise(stringResource(R.string.onboarding_promise_offline))
                Promise(stringResource(R.string.onboarding_promise_only_financial))
                Promise(stringResource(R.string.onboarding_promise_revocable))
            }

            AccessCard(
                hasAccess = state.hasNotificationAccess,
                onOpenSettings = onOpenNotificationSettings,
            )

            if (state.showBatteryHint) {
                BatteryCard(onOpenSettings = onOpenBatterySettings)
            }

            if (state.canImportPastMessages) {
                ImportCard(status = state.importStatus, onStartImport = onStartImport)
            }

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.onboarding_done))
            }
        }
    }
}

@Composable
private fun Promise(text: String) {
    val spacing = MezgebTheme.spacing
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(end = spacing.sm, top = spacing.xxs)
                .size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccessCard(hasAccess: Boolean, onOpenSettings: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasAccess) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (hasAccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(end = spacing.sm),
                )
                Text(
                    text = stringResource(
                        if (hasAccess) {
                            R.string.onboarding_access_on
                        } else {
                            R.string.onboarding_access_off
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (!hasAccess) {
                Text(
                    text = stringResource(R.string.onboarding_grant_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.onboarding_grant_action))
                }
            } else {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.onboarding_grant_action))
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(onOpenSettings: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.onboarding_battery_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_battery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.onboarding_battery_action))
            }
        }
    }
}

@Composable
private fun ImportCard(status: SmsImportStatus, onStartImport: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.onboarding_import_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onboarding_import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (status) {
                is SmsImportStatus.Running -> {
                    Text(
                        text = stringResource(
                            R.string.onboarding_import_running,
                            status.scanned,
                            status.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status.total > 0) {
                        LinearProgressIndicator(
                            progress = { status.scanned.toFloat() / status.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is SmsImportStatus.Finished -> Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    // Two sentences rather than one, because each half counts something
                    // different and a single plurals entry can only agree with one of
                    // them. "1 payment added. 3 were already in your ledger." needs both
                    // to be chosen independently.
                    Text(
                        text = pluralStringResource(
                            R.plurals.onboarding_import_finished_added,
                            status.imported,
                            status.imported,
                        ) + " " + pluralStringResource(
                            R.plurals.onboarding_import_finished_existing,
                            status.duplicates,
                            status.duplicates,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status.skippedCredentials > 0) {
                        // Where the missing ones went. Silence here would look like a
                        // bug, and the reason is one the shop should hear.
                        Text(
                            text = pluralStringResource(
                                R.plurals.onboarding_import_skipped_credentials,
                                status.skippedCredentials,
                                status.skippedCredentials,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SmsImportStatus.Failed -> Text(
                    text = stringResource(R.string.onboarding_import_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                SmsImportStatus.Idle, SmsImportStatus.Unavailable -> Unit
            }

            if (status !is SmsImportStatus.Running) {
                OutlinedButton(
                    onClick = onStartImport,
                    modifier = Modifier.heightIn(min = spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.onboarding_import_action))
                }
            }
        }
    }
}

/**
 * Adds the notification permission alongside the importer's own, on the versions
 * that need it, so the progress notification is actually visible. Kept out of the
 * importer's own list because a refusal here must not stop the import.
 */
private fun withNotificationPermission(permissions: List<String>): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        (permissions + Manifest.permission.POST_NOTIFICATIONS).toTypedArray()
    } else {
        permissions.toTypedArray()
    }

/**
 * Vendor settings screens are undocumented and firmware-specific; the stock ones can
 * be missing on cut-down ROMs. A missing settings screen is a disappointment, not a
 * crash.
 */
private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

@Preview(name = "Onboarding", showBackground = true, heightDp = 900)
@Composable
private fun OnboardingScreenPreview() {
    MezgebTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                isLoading = false,
                hasNotificationAccess = false,
                canImportPastMessages = true,
                importStatus = SmsImportStatus.Idle,
                showBatteryHint = true,
            ),
            onEvent = {},
            onOpenNotificationSettings = {},
            onOpenBatterySettings = {},
            onStartImport = {},
            onFinish = {},
        )
    }
}

@Preview(name = "Onboarding (አማርኛ)", showBackground = true, heightDp = 900, locale = "am")
@Composable
private fun OnboardingScreenAmharicPreview() {
    MezgebTheme {
        OnboardingScreen(
            state = OnboardingUiState(
                isLoading = false,
                hasNotificationAccess = true,
                canImportPastMessages = true,
                importStatus = SmsImportStatus.Running(imported = 12, scanned = 240, total = 800),
                showBatteryHint = false,
            ),
            onEvent = {},
            onOpenNotificationSettings = {},
            onOpenBatterySettings = {},
            onStartImport = {},
            onFinish = {},
        )
    }
}
