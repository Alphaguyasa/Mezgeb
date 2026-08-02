package et.alpha.mezgeb.ui.cash

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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.ui.common.rememberDayLabel
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import java.time.ZoneId

@Composable
fun CashEntryRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CashEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onBack()
    }

    CashEntryScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashEntryScreen(
    state: CashEntryUiState,
    onEvent: (CashEntryEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    var showDatePicker by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val errorText = stringResource(R.string.cash_save_failed)
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            snackbarHostState.showSnackbar(errorText)
            onEvent(CashEntryEvent.ErrorShown)
        }
    }

    MezgebScaffold(
        title = stringResource(R.string.cash_title),
        modifier = modifier,
        navigationIcon = { BackButton(onClick = onBack) },
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
            // The amount, at the top and at the size of the decision being made.
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

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Direction.entries.forEach { direction ->
                    FilterChip(
                        selected = state.direction == direction,
                        onClick = { onEvent(CashEntryEvent.DirectionChanged(direction)) },
                        label = {
                            Text(
                                stringResource(
                                    when (direction) {
                                        Direction.IN -> R.string.direction_in
                                        Direction.OUT -> R.string.direction_out
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            AmountKeypad(
                onDigit = { onEvent(CashEntryEvent.DigitPressed(it)) },
                onDoubleZero = { onEvent(CashEntryEvent.DoubleZeroPressed) },
                onBackspace = { onEvent(CashEntryEvent.BackspacePressed) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Box {
                    FilterChip(
                        selected = state.categoryId != null,
                        onClick = { categoryExpanded = true },
                        label = {
                            Text(
                                state.categories.firstOrNull { it.id == state.categoryId }?.name
                                    ?: stringResource(R.string.detail_category_none),
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_category_none)) },
                            onClick = {
                                onEvent(CashEntryEvent.CategoryPicked(null))
                                categoryExpanded = false
                            },
                        )
                        state.categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    onEvent(CashEntryEvent.CategoryPicked(category.id))
                                    categoryExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.heightIn(min = spacing.touchTarget),
                ) {
                    Text(rememberDayLabel(state.occurredAt))
                }
            }

            OutlinedTextField(
                value = state.counterparty,
                onValueChange = { onEvent(CashEntryEvent.CounterpartyChanged(it)) },
                label = { Text(stringResource(R.string.cash_counterparty)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = { onEvent(CashEntryEvent.NoteChanged(it)) },
                label = { Text(stringResource(R.string.detail_field_note)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onEvent(CashEntryEvent.Save) },
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
                                CashEntryEvent.DateChanged(
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

@Preview(name = "Cash entry", showBackground = true, heightDp = 900)
@Composable
private fun CashEntryPreview() {
    MezgebTheme {
        CashEntryScreen(
            state = CashEntryUiState(amountMinor = 45_000, occurredAt = 1_782_983_640_000L),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview(name = "Cash entry (አማርኛ)", showBackground = true, heightDp = 900, locale = "am")
@Composable
private fun CashEntryAmharicPreview() {
    MezgebTheme {
        CashEntryScreen(
            state = CashEntryUiState(occurredAt = 1_782_983_640_000L),
            onEvent = {},
            onBack = {},
        )
    }
}
