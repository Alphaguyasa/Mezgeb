package et.alpha.mezgeb.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.AmountKeypad
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.ChannelChip
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.credit.RepaymentMatch
import et.alpha.mezgeb.ui.common.rememberDayLabel
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import java.time.ZoneId

@Composable
fun CreditEntryRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreditEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onBack()
    }

    CreditEntryScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditEntryScreen(
    state: CreditEntryUiState,
    onEvent: (CreditEntryEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    val errorText = stringResource(R.string.credit_save_failed)

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            snackbarHostState.showSnackbar(errorText)
            onEvent(CreditEntryEvent.ErrorShown)
        }
    }

    MezgebScaffold(
        title = stringResource(
            if (state.isRepayment) R.string.credit_record_repayment else R.string.credit_add_sale,
        ),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
        snackbarHostState = snackbarHostState,
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            state.customer?.let { customer ->
                Text(
                    text = customer.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = state.amountText + " " + MoneyFormatter.DEFAULT_CURRENCY,
                    style = MaterialTheme.typography.displayMedium,
                    color = if (state.amountMinor > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            AmountKeypad(
                onDigit = { onEvent(CreditEntryEvent.DigitPressed(it)) },
                onDoubleZero = { onEvent(CreditEntryEvent.DoubleZeroPressed) },
                onBackspace = { onEvent(CreditEntryEvent.BackspacePressed) },
            )

            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.heightIn(min = spacing.touchTarget),
            ) {
                Text(rememberDayLabel(state.occurredAt))
            }

            OutlinedTextField(
                value = state.note,
                onValueChange = { onEvent(CreditEntryEvent.NoteChanged(it)) },
                label = { Text(stringResource(R.string.detail_field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isRepayment) {
                MatchList(
                    matches = state.matches,
                    linkedTransactionId = state.linkedTransactionId,
                    onPick = { onEvent(CreditEntryEvent.TransactionPicked(it)) },
                )
            }

            Button(
                onClick = { onEvent(CreditEntryEvent.Save) },
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.occurredAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            val zone = ZoneId.systemDefault()
                            val timeOfDay = state.occurredAt -
                                MezgebDates.startOfDay(state.occurredAt, zone)
                            onEvent(
                                CreditEntryEvent.DateChanged(
                                    MezgebDates.startOfDay(picked, zone) + timeOfDay,
                                ),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Captured payments this repayment might be. Suggested, never assumed.
 *
 * Each row states why it is being offered — same amount, same phone — because a
 * ranked list with no stated reason is one people either trust blindly or ignore.
 */
@Composable
private fun MatchList(
    matches: List<RepaymentMatch>,
    linkedTransactionId: String?,
    onPick: (String) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.credit_match_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        if (matches.isEmpty()) {
            Text(
                text = stringResource(R.string.credit_match_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        matches.forEach { match ->
            val isLinked = match.transaction.id == linkedTransactionId
            Card(
                onClick = { onPick(match.transaction.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isLinked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(match.transaction.id) }
                        .padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    RadioButton(selected = isLinked, onClick = { onPick(match.transaction.id) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = MoneyFormatter.format(match.transaction.amountMinor),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = (match.transaction.counterpartyName ?: "") + " · " +
                                rememberDayLabel(match.transaction.occurredAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val reason = when {
                            match.matchesAmount && match.matchesPhone ->
                                R.string.credit_match_amount_and_phone
                            match.matchesAmount -> R.string.credit_match_amount
                            match.matchesPhone -> R.string.credit_match_phone
                            else -> null
                        }
                        reason?.let {
                            Text(
                                text = stringResource(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    ChannelChip(match.transaction.channel)
                }
            }
        }
    }
}

@Preview(name = "Record repayment", showBackground = true, heightDp = 900)
@Composable
private fun CreditEntryPreview() {
    MezgebTheme {
        CreditEntryScreen(
            state = CreditEntryUiState(
                isLoading = false,
                isRepayment = true,
                amountMinor = 45_000,
                occurredAt = 1_782_983_640_000L,
                customer = et.alpha.mezgeb.domain.model.Customer(
                    id = "1",
                    name = "Abebe Bekele",
                    phone = "+251911000001",
                    note = null,
                    createdAt = 0L,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
