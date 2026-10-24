package et.alpha.mezgeb.ui.reconcile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.domain.reconcile.GapExplanation
import et.alpha.mezgeb.domain.reconcile.PeriodReconciliation
import et.alpha.mezgeb.ui.common.rememberDayLabel
import et.alpha.mezgeb.util.MoneyFormatter
import kotlin.math.abs

@Composable
fun ReconcileRoute(
    onBack: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenLedger: () -> Unit,
    onOpenCustomers: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReconcileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReconcileScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        // Every explanation is a lead, so every one leads somewhere.
        onExplanationTapped = { explanation ->
            when (explanation) {
                is GapExplanation.UnmatchedMessages -> onOpenReview()
                is GapExplanation.OutstandingCredit -> onOpenCustomers()
                is GapExplanation.UncategorizedTransactions,
                is GapExplanation.PossibleDuplicates,
                is GapExplanation.EditedSincePeriodOpened,
                -> onOpenLedger()
            }
        },
        modifier = modifier,
    )
}

@Composable
fun ReconcileScreen(
    state: ReconcileUiState,
    onEvent: (ReconcileEvent) -> Unit,
    onBack: () -> Unit,
    onExplanationTapped: (GapExplanation) -> Unit,
    modifier: Modifier = Modifier,
) {
    MezgebScaffold(
        title = stringResource(R.string.destination_reconcile),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
    ) { inner ->
        LazyColumn(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            if (!state.hasOpenPeriod) {
                item("picker") { PeriodPicker(state = state, onEvent = onEvent) }
            } else {
                item("open") {
                    OpenPeriod(
                        state = state,
                        onEvent = onEvent,
                        onExplanationTapped = onExplanationTapped,
                    )
                }
            }

            if (state.closedSessions.isNotEmpty()) {
                item("history_heading") {
                    Text(
                        text = stringResource(R.string.reconcile_history),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(MezgebTheme.spacing.screenGutter),
                    )
                }
                items(state.closedSessions, key = { it.id }) { session ->
                    ClosedPeriodRow(
                        session = session,
                        onClick = { onEvent(ReconcileEvent.ViewSnapshot(session)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    state.viewingSnapshot?.let { session ->
        SnapshotDialog(session = session, onDismiss = { onEvent(ReconcileEvent.DismissSnapshot) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPicker(state: ReconcileUiState, onEvent: (ReconcileEvent) -> Unit) {
    val spacing = MezgebTheme.spacing
    var showRangePicker by remember { mutableStateOf(false) }

    Column(
        Modifier.padding(spacing.screenGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(R.string.reconcile_pick_period),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Scrolls rather than squeezes, as in the ledger's filter bar. In Amharic the
        // three chips can be wider than a 360dp phone.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PeriodChoice.entries.forEach { choice ->
                FilterChip(
                    selected = state.periodChoice == choice,
                    onClick = {
                        if (choice == PeriodChoice.CUSTOM) {
                            showRangePicker = true
                        } else {
                            onEvent(ReconcileEvent.PeriodChosen(choice))
                        }
                    },
                    label = { Text(stringResource(choice.labelRes())) },
                )
            }
        }
        if (state.periodChoice == PeriodChoice.CUSTOM && state.customRange != null) {
            Text(
                text = rememberDayLabel(state.customRange.first) + " – " +
                    rememberDayLabel(state.customRange.last),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { onEvent(ReconcileEvent.OpenPeriod) },
            enabled = state.periodChoice != PeriodChoice.CUSTOM || state.customRange != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.touchTarget),
        ) {
            Text(stringResource(R.string.reconcile_open_period))
        }
    }

    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val from = pickerState.selectedStartDateMillis
                        val to = pickerState.selectedEndDateMillis
                        if (from != null && to != null) {
                            onEvent(ReconcileEvent.CustomRangeChosen(from, to))
                        }
                        showRangePicker = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DateRangePicker(state = pickerState)
        }
    }
}

@Composable
private fun OpenPeriod(
    state: ReconcileUiState,
    onEvent: (ReconcileEvent) -> Unit,
    onExplanationTapped: (GapExplanation) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    val session = state.openSession ?: return
    val figures = state.reconciliation

    Column(
        Modifier.padding(spacing.screenGutter),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Text(
            text = rememberDayLabel(session.periodStart) + " – " +
                rememberDayLabel(session.periodEnd),
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            value = state.expectedInput,
            onValueChange = { onEvent(ReconcileEvent.ExpectedChanged(it)) },
            label = { Text(stringResource(R.string.reconcile_expected_label)) },
            supportingText = {
                Text(
                    stringResource(
                        if (state.expectedIsInvalid) {
                            R.string.reconcile_expected_invalid
                        } else {
                            R.string.reconcile_expected_hint
                        },
                    ),
                )
            },
            isError = state.expectedIsInvalid,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onEvent(ReconcileEvent.SaveExpected) },
            modifier = Modifier.heightIn(min = spacing.touchTarget),
        ) {
            Text(stringResource(R.string.reconcile_check))
        }

        if (figures != null) {
            Breakdown(figures)
            GapCard(figures)

            figures.explanations.forEach { explanation ->
                ExplanationRow(
                    explanation = explanation,
                    onClick = { onExplanationTapped(explanation) },
                )
            }

            if (state.canAddNote) {
                OutlinedTextField(
                    value = state.noteInput,
                    onValueChange = { onEvent(ReconcileEvent.NoteChanged(it)) },
                    label = { Text(stringResource(R.string.reconcile_note_label)) },
                    supportingText = { Text(stringResource(R.string.reconcile_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { onEvent(ReconcileEvent.ClosePeriod) },
                enabled = state.canClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.reconcile_close_period))
            }
        }
    }
}

@Composable
private fun Breakdown(figures: PeriodReconciliation) {
    val spacing = MezgebTheme.spacing
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing.lg)) {
            FigureRow(R.string.reconcile_captured_in, figures.capturedInMinor)
            FigureRow(R.string.reconcile_cash_recorded, figures.cashRecordedMinor)
            FigureRow(R.string.reconcile_credit_given, figures.creditGivenMinor)
            FigureRow(
                R.string.reconcile_credit_repaid,
                figures.creditRepaidMinor,
                subtracted = true,
            )
            HorizontalDivider(Modifier.padding(vertical = spacing.sm))
            FigureRow(R.string.reconcile_accounted_for, figures.accountedForMinor, emphasise = true)
        }
    }
}

/**
 * The gap, or the confirmation that there is none.
 *
 * Balanced is drawn as an event, not as a zero. It is the moment the app justifies
 * itself, and a shop owner who reaches it should feel it rather than have to read a
 * row of numbers to work out that nothing is wrong.
 */
@Composable
private fun GapCard(figures: PeriodReconciliation) {
    val spacing = MezgebTheme.spacing
    val gap = figures.gapMinor ?: return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (figures.isBalanced) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (figures.isBalanced) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (figures.isBalanced) {
                Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null)
                Text(
                    text = stringResource(R.string.reconcile_balanced_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.reconcile_balanced_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = stringResource(
                        if (figures.isShort) {
                            R.string.reconcile_gap_short
                        } else {
                            R.string.reconcile_gap_over
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = MoneyFormatter.format(abs(gap)),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.reconcile_gap_explain),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ExplanationRow(explanation: GapExplanation, onClick: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = spacing.touchTarget)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = when (explanation) {
                    is GapExplanation.UnmatchedMessages -> pluralStringResource(
                        R.plurals.reconcile_explain_unmatched,
                        explanation.count,
                        explanation.count,
                    )

                    is GapExplanation.UncategorizedTransactions -> pluralStringResource(
                        R.plurals.reconcile_explain_uncategorised,
                        explanation.count,
                        explanation.count,
                    )

                    is GapExplanation.PossibleDuplicates -> pluralStringResource(
                        R.plurals.reconcile_explain_possible_duplicates,
                        explanation.count,
                        explanation.count,
                    )

                    is GapExplanation.OutstandingCredit -> pluralStringResource(
                        R.plurals.reconcile_explain_credit,
                        explanation.customerCount,
                        explanation.customerCount,
                    )

                    is GapExplanation.EditedSincePeriodOpened -> pluralStringResource(
                        R.plurals.reconcile_explain_edited,
                        explanation.count,
                        explanation.count,
                    )
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            explanation.potentialMinor?.let { amount ->
                Text(
                    text = if (explanation is GapExplanation.UnmatchedMessages) {
                        // Read out of the text by guesswork, so it is stated as a guess.
                        stringResource(
                            R.string.reconcile_explain_roughly,
                            MoneyFormatter.format(amount),
                        )
                    } else {
                        MoneyFormatter.format(amount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
        )
    }
}

@Composable
private fun ClosedPeriodRow(session: ReconciliationSession, onClick: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = spacing.touchTarget)
            .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = rememberDayLabel(session.periodStart) + " – " +
                    rememberDayLabel(session.periodEnd),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = MoneyFormatter.format(session.capturedInMinor + session.cashRecordedMinor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
        )
    }
}

/**
 * A closed period, exactly as it was closed.
 *
 * Read-only by construction: these are the snapshotted columns, not a recomputation.
 * Editing a transaction inside a closed period is allowed and changes nothing here.
 */
@Composable
private fun SnapshotDialog(session: ReconciliationSession, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                rememberDayLabel(session.periodStart) + " – " + rememberDayLabel(session.periodEnd),
            )
        },
        text = {
            Column {
                session.expectedTotalMinor?.let {
                    FigureRow(R.string.reconcile_expected_label, it)
                }
                FigureRow(R.string.reconcile_captured_in, session.capturedInMinor)
                FigureRow(R.string.reconcile_cash_recorded, session.cashRecordedMinor)
                FigureRow(R.string.reconcile_credit_given, session.creditGivenMinor)
                FigureRow(
                    R.string.reconcile_credit_repaid,
                    session.creditRepaidMinor,
                    subtracted = true,
                )
                session.note?.let { note ->
                    Text(
                        text = stringResource(R.string.reconcile_snapshot_owner_note, note),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = MezgebTheme.spacing.sm),
                    )
                }
                Text(
                    text = stringResource(R.string.reconcile_snapshot_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MezgebTheme.spacing.sm),
                )
            }
        },
        confirmButton = {
            // Nothing here can be changed, so there is nothing to cancel.
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * One line of the breakdown: a label and an amount, read by TalkBack as one sentence.
 *
 * [subtracted] marks a figure that counts against the total, such as credit repaid.
 * The eye gets a minus sign. TalkBack gets the word, because it reads "−500.00" as a
 * symbol in front of digits, and some speech engines drop the sign altogether, which
 * would make a subtraction sound like an addition.
 */
@Composable
private fun FigureRow(
    labelRes: Int,
    amountMinor: Long,
    subtracted: Boolean = false,
    emphasise: Boolean = false,
) {
    val label = stringResource(labelRes)
    val shownMinor = if (subtracted) -amountMinor else amountMinor
    val spoken = stringResource(
        if (shownMinor < 0) {
            R.string.reconcile_figure_less_content_description
        } else {
            R.string.reconcile_figure_content_description
        },
        label,
        MoneyFormatter.formatUnsigned(shownMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = MezgebTheme.spacing.xs)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = MoneyFormatter.format(shownMinor),
            style = if (emphasise) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun PeriodChoice.labelRes(): Int = when (this) {
    PeriodChoice.TODAY -> R.string.ledger_filter_date_today
    PeriodChoice.THIS_WEEK -> R.string.reconcile_period_week
    PeriodChoice.CUSTOM -> R.string.reconcile_period_custom
}

@Preview(name = "Reconcile (balanced)", showBackground = true, heightDp = 900)
@Composable
private fun ReconcileBalancedPreview() {
    MezgebTheme {
        ReconcileScreen(
            state = ReconcileUiState(
                isLoading = false,
                openSession = previewSession(),
                reconciliation = PeriodReconciliation(
                    periodStart = 1_782_950_400_000L,
                    periodEnd = 1_783_036_799_999L,
                    expectedTotalMinor = 245_000,
                    capturedInMinor = 200_000,
                    capturedOutMinor = 15_000,
                    cashRecordedMinor = 45_000,
                    creditGivenMinor = 0,
                    creditRepaidMinor = 0,
                ),
                expectedInput = "2450.00",
            ),
            onEvent = {},
            onBack = {},
            onExplanationTapped = {},
        )
    }
}

@Preview(name = "Reconcile (gap, አማርኛ)", showBackground = true, heightDp = 900, locale = "am")
@Composable
private fun ReconcileGapPreview() {
    MezgebTheme {
        ReconcileScreen(
            state = ReconcileUiState(
                isLoading = false,
                openSession = previewSession(),
                reconciliation = PeriodReconciliation(
                    periodStart = 1_782_950_400_000L,
                    periodEnd = 1_783_036_799_999L,
                    expectedTotalMinor = 300_000,
                    capturedInMinor = 200_000,
                    capturedOutMinor = 15_000,
                    cashRecordedMinor = 45_000,
                    creditGivenMinor = 10_000,
                    creditRepaidMinor = 0,
                    explanations = listOf(
                        GapExplanation.UnmatchedMessages(4, 32_000, amountsFound = 3),
                        GapExplanation.UncategorizedTransactions(2, 12_000),
                        GapExplanation.PossibleDuplicates(1, 9_000),
                        GapExplanation.EditedSincePeriodOpened(1),
                    ),
                ),
                expectedInput = "3000.00",
            ),
            onEvent = {},
            onBack = {},
            onExplanationTapped = {},
        )
    }
}

private fun previewSession() = ReconciliationSession(
    id = "s1",
    periodStart = 1_782_950_400_000L,
    periodEnd = 1_783_036_799_999L,
    openedAt = 1_782_950_400_000L,
    expectedTotalMinor = 245_000,
    capturedInMinor = 0,
    capturedOutMinor = 0,
    cashRecordedMinor = 0,
    creditGivenMinor = 0,
    creditRepaidMinor = 0,
    status = SessionStatus.OPEN,
    closedAt = null,
    note = null,
)
