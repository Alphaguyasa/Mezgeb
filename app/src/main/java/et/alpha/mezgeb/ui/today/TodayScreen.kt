package et.alpha.mezgeb.ui.today

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.CaptureHealth
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.designsystem.component.MoneyText
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.ui.common.TransactionRow
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.common.spokenNet
import et.alpha.mezgeb.util.MoneyFormatter

@Composable
fun TodayRoute(
    onOpenReview: () -> Unit,
    onOpenReconcile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onRecordCashSale: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Coming back to Today is when capture access may have changed, and when the day
    // may have rolled over while the app sat in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onEvent(TodayEvent.Refresh)
    }

    TodayScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenReview = onOpenReview,
        onOpenReconcile = onOpenReconcile,
        onOpenSettings = onOpenSettings,
        onOpenTransaction = onOpenTransaction,
        onRecordCashSale = onRecordCashSale,
        onFixCapture = { context.startSafely(viewModel.healthFixIntent(state.captureHealth)) },
        modifier = modifier,
    )
}

@Composable
fun TodayScreen(
    state: TodayUiState,
    onEvent: (TodayEvent) -> Unit,
    onOpenReview: () -> Unit,
    onOpenReconcile: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransaction: (String) -> Unit,
    onRecordCashSale: () -> Unit,
    onFixCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    MezgebScaffold(
        title = stringResource(R.string.destination_today),
        modifier = modifier,
        actions = {
            // Sized to the 48dp floor for the same reason as BackButton: Material's
            // IconButton reports a 40dp node, under the floor this app holds itself to.
            IconButton(
                onClick = onOpenReview,
                modifier = Modifier.size(spacing.touchTarget),
            ) {
                BadgedBox(
                    badge = {
                        if (state.reviewQueueCount > 0) {
                            Badge { Text(state.reviewQueueCount.toString()) }
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = stringResource(R.string.cd_open_review),
                    )
                }
            }
            IconButton(
                onClick = onOpenReconcile,
                modifier = Modifier.size(spacing.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.cd_open_reconcile),
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(spacing.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.cd_open_settings),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecordCashSale,
                // Material clears the label's semantics and names the button from its
                // icon, so a null description here leaves TalkBack saying only "button".
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.today_record_cash),
                    )
                },
                text = { Text(stringResource(R.string.today_record_cash)) },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            item("totals") {
                TodayTotals(
                    totalInMinor = state.totalInMinor,
                    totalOutMinor = state.totalOutMinor,
                    netMinor = state.netMinor,
                    modifier = Modifier.padding(spacing.screenGutter),
                )
            }

            if (state.captureHealth != CaptureHealth.HEALTHY) {
                item("health") {
                    CaptureHealthBanner(
                        health = state.captureHealth,
                        onFix = onFixCapture,
                        modifier = Modifier.padding(
                            start = spacing.screenGutter,
                            end = spacing.screenGutter,
                            bottom = spacing.lg,
                        ),
                    )
                }
            }

            if (state.reviewQueueCount > 0) {
                item("review") {
                    ReviewQueueRow(count = state.reviewQueueCount, onClick = onOpenReview)
                    HorizontalDivider()
                }
            }

            if (state.isEmpty && !state.isLoading) {
                item("empty") {
                    EmptyState(
                        title = stringResource(R.string.today_empty_title),
                        body = stringResource(
                            if (state.hasEverCaptured) {
                                R.string.today_empty_body_quiet_day
                            } else {
                                R.string.today_empty_body_nothing_yet
                            },
                        ),
                        modifier = Modifier.padding(top = spacing.xl),
                    )
                }
            }

            items(state.recent, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    onClick = { onOpenTransaction(transaction.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TodayTotals(
    totalInMinor: Long,
    totalOutMinor: Long,
    netMinor: Long,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            // The number the owner opens the app for, at the size she can read at
            // arm's length in daylight without putting her glasses on.
            Text(
                text = stringResource(R.string.today_total_in),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoneyText(
                amountMinor = totalInMinor,
                direction = Direction.IN,
                style = MaterialTheme.typography.displaySmall,
                showCurrency = true,
                showIcon = false,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xl)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.today_total_out),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        amountMinor = totalOutMinor,
                        direction = Direction.OUT,
                        style = MaterialTheme.typography.titleLarge,
                        showIcon = false,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.today_net),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Not a MoneyText: the net is neither money in nor money out, so it
                    // takes neither colour. It still has to be read as words, or
                    // TalkBack reads the sign as a symbol in front of the digits.
                    // The semantics go on a wrapper, as in MoneyText, so they replace
                    // the Text's own rather than sit beside them on the same node.
                    val spokenNet = spokenNet(netMinor)
                    Box(Modifier.clearAndSetSemantics { contentDescription = spokenNet }) {
                        Text(
                            text = MoneyFormatter.format(netMinor, signed = true),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The banner that makes silent capture failure visible.
 *
 * Deliberately not dismissible: on the devices this app is built for, a listener
 * killed by the battery manager is the single most likely way for the ledger to go
 * quietly wrong, and a dismissed warning is a warning that was never given.
 */
@Composable
private fun CaptureHealthBanner(
    health: CaptureHealth,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(end = spacing.sm),
                )
                Text(
                    text = stringResource(
                        when (health) {
                            CaptureHealth.NO_ACCESS -> R.string.capture_health_no_access_title
                            else -> R.string.capture_health_silent_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(
                    when (health) {
                        CaptureHealth.NO_ACCESS -> R.string.capture_health_no_access_body
                        else -> R.string.capture_health_silent_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onFix, modifier = Modifier.heightIn(min = spacing.touchTarget)) {
                Text(
                    stringResource(
                        when (health) {
                            CaptureHealth.NO_ACCESS -> R.string.capture_health_no_access_action
                            else -> R.string.capture_health_silent_action
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ReviewQueueRow(count: Int, onClick: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = spacing.touchTarget)
                .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.today_review_queue, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Context.startSafely(intent: Intent?) {
    if (intent == null) return
    runCatching { startActivity(intent) }
}

@Preview(name = "Today", showBackground = true, heightDp = 800)
@Composable
private fun TodayScreenPreview() {
    MezgebTheme {
        TodayScreen(
            state = TodayUiState(
                isLoading = false,
                totalInMinor = 245_000,
                totalOutMinor = 32_500,
                reviewQueueCount = 3,
                recent = previewRows(),
                hasEverCaptured = true,
            ),
            onEvent = {},
            onOpenReview = {},
            onOpenReconcile = {},
            onOpenSettings = {},
            onOpenTransaction = {},
            onRecordCashSale = {},
            onFixCapture = {},
        )
    }
}

/** Amharic render check — CONVENTIONS.md requires Ethiopic to be verified in a preview. */
@Preview(name = "Today (አማርኛ)", showBackground = true, heightDp = 800, locale = "am")
@Composable
private fun TodayScreenAmharicPreview() {
    MezgebTheme {
        TodayScreen(
            state = TodayUiState(
                isLoading = false,
                captureHealth = CaptureHealth.SILENT_TOO_LONG,
                recent = previewRows(),
                hasEverCaptured = true,
            ),
            onEvent = {},
            onOpenReview = {},
            onOpenReconcile = {},
            onOpenSettings = {},
            onOpenTransaction = {},
            onRecordCashSale = {},
            onFixCapture = {},
        )
    }
}

private fun previewRows() = listOf(
    TransactionUiModel(
        id = "1",
        direction = Direction.IN,
        amountMinor = 45_000,
        channel = Channel.TELEBIRR,
        counterparty = "ABEBE",
        occurredAt = 1_782_983_640_000L,
        categoryName = "Sales",
        isEdited = false,
        isReconciled = false,
    ),
    TransactionUiModel(
        id = "2",
        direction = Direction.OUT,
        amountMinor = 32_500,
        channel = Channel.CASH,
        counterparty = null,
        occurredAt = 1_782_990_000_000L,
        categoryName = null,
        isEdited = true,
        isReconciled = false,
    ),
)
