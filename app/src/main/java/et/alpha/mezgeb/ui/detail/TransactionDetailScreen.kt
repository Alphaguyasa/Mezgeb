package et.alpha.mezgeb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.designsystem.component.MoneyText
import et.alpha.mezgeb.designsystem.component.label
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.ui.common.rememberDayLabel
import et.alpha.mezgeb.ui.common.rememberTimeLabel
import et.alpha.mezgeb.util.MezgebDates
import java.time.ZoneId

@Composable
fun TransactionDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TransactionDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Deleting the row leaves nothing to look at.
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    TransactionDetailScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun TransactionDetailScreen(
    state: TransactionDetailUiState,
    onEvent: (TransactionDetailEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    MezgebScaffold(
        title = stringResource(R.string.detail_title),
        modifier = modifier,
        navigationIcon = { BackButton(onClick = onBack) },
        actions = {
            if (state.transaction != null && !state.isEditing) {
                IconButton(onClick = { onEvent(TransactionDetailEvent.StartEdit) }) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = stringResource(R.string.detail_edit),
                    )
                }
            }
        },
    ) { inner ->
        val transaction = state.transaction
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (transaction == null) {
                if (!state.isLoading) {
                    Text(
                        text = stringResource(R.string.detail_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                return@Column
            }

            val editor = state.editor
            if (editor == null) {
                ViewMode(state = state, transaction = transaction, onEvent = onEvent)
            } else {
                EditMode(editor = editor, onEvent = onEvent)
            }
        }
    }

    if (state.isConfirmingDelete) {
        AlertDialog(
            onDismissRequest = { onEvent(TransactionDetailEvent.DismissDelete) },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onEvent(TransactionDetailEvent.ConfirmDelete) }) {
                    Text(stringResource(R.string.detail_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(TransactionDetailEvent.DismissDelete) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ViewMode(
    state: TransactionDetailUiState,
    transaction: Transaction,
    onEvent: (TransactionDetailEvent) -> Unit,
) {
    val spacing = MezgebTheme.spacing

    MoneyText(
        amountMinor = transaction.amountMinor,
        direction = transaction.direction,
        style = MaterialTheme.typography.displaySmall,
        showCurrency = true,
        showIcon = false,
    )

    if (transaction.isEdited) {
        Text(
            text = stringResource(R.string.detail_edited_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (transaction.isPossibleDuplicate) {
        // The app only suspects. Deleting is already on the top bar; this is the other
        // answer, for the shop owner who checked and found two real payments.
        Text(
            text = stringResource(R.string.detail_possible_duplicate_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onEvent(TransactionDetailEvent.DismissPossibleDuplicate) }) {
            Text(stringResource(R.string.detail_not_a_duplicate))
        }
    }

    Card {
        Column(Modifier.padding(spacing.lg)) {
            DetailRow(
                label = stringResource(R.string.detail_field_direction),
                value = stringResource(
                    when (transaction.direction) {
                        Direction.IN -> R.string.direction_in
                        Direction.OUT -> R.string.direction_out
                    },
                ),
            )
            DetailRow(
                label = stringResource(R.string.detail_field_channel),
                value = transaction.channel.label(),
            )
            DetailRow(
                label = stringResource(R.string.detail_field_when),
                value = rememberDayLabel(transaction.occurredAt) + " " +
                    rememberTimeLabel(transaction.occurredAt),
            )
            transaction.counterpartyName?.let {
                DetailRow(stringResource(R.string.detail_field_counterparty), it)
            }
            transaction.counterpartyPhone?.let {
                DetailRow(stringResource(R.string.detail_field_phone), it)
            }
            transaction.providerTxnId?.let {
                DetailRow(stringResource(R.string.detail_field_reference), it)
            }
            transaction.balanceAfterMinor?.let {
                DetailRow(
                    label = stringResource(R.string.detail_field_balance),
                    value = et.alpha.mezgeb.util.MoneyFormatter.format(it),
                    valueIsAmount = true,
                )
            }
            transaction.note?.let {
                DetailRow(stringResource(R.string.detail_field_note), it)
            }
            state.linkedCustomerName?.let {
                DetailRow(stringResource(R.string.detail_field_settles_credit), it)
            }
        }
    }

    CategorySection(state = state, onEvent = onEvent)

    // The row is the control, as in Settings (ROADMAP R17). A Switch with its own
    // click handler is a separate control with no name, read as "switch, off" with
    // nothing to say what it marks.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .toggleable(
                value = transaction.isReconciled,
                role = Role.Switch,
                onValueChange = { onEvent(TransactionDetailEvent.ToggleReconciled) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.detail_reconciled),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = transaction.isReconciled, onCheckedChange = null)
    }

    OriginalMessageSection(state = state, onEvent = onEvent)

    OutlinedButton(
        onClick = { onEvent(TransactionDetailEvent.RequestDelete) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget),
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = null,
            modifier = Modifier.padding(end = spacing.sm),
        )
        Text(stringResource(R.string.detail_delete))
    }
}

@Composable
private fun CategorySection(
    state: TransactionDetailUiState,
    onEvent: (TransactionDetailEvent) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    var expanded by remember { mutableStateOf(false) }
    var alwaysApply by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.detail_field_category),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            FilterChip(
                selected = state.categoryName != null,
                onClick = { expanded = true },
                label = {
                    Text(state.categoryName ?: stringResource(R.string.detail_category_none))
                },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_category_none)) },
                    onClick = {
                        onEvent(TransactionDetailEvent.CategoryPicked(null, alwaysApply = false))
                        expanded = false
                    },
                )
                state.categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        onClick = {
                            onEvent(
                                TransactionDetailEvent.CategoryPicked(category.id, alwaysApply),
                            )
                            expanded = false
                        },
                    )
                }
            }
        }
        if (state.canCreateCategoryRule) {
            // The row alone is the control, for the same reason as the Reconciled switch:
            // a Checkbox with its own handler was a second, nameless target.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.touchTarget)
                    .toggleable(
                        value = alwaysApply,
                        role = Role.Checkbox,
                        onValueChange = { alwaysApply = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = alwaysApply, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.detail_category_always),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The message, verbatim, behind one tap.
 *
 * Collapsed by default because most of the time the parsed table is enough — but
 * always present, never summarised. This is the answer to "where did this number
 * come from", and an app that handles someone's money owes them that answer.
 */
@Composable
private fun OriginalMessageSection(
    state: TransactionDetailUiState,
    onEvent: (TransactionDetailEvent) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    val raw = state.rawMessage ?: return
    // The arrow says open or closed only to someone who can see it. TalkBack reads
    // the row as a button with its state, "Message from 127, hidden", and says what a
    // double tap will do. ROADMAP R28.
    val stateLabel = stringResource(
        if (state.isBodyExpanded) {
            R.string.detail_original_message_expanded
        } else {
            R.string.detail_original_message_collapsed
        },
    )
    val actionLabel = stringResource(
        if (state.isBodyExpanded) {
            R.string.detail_original_message_hide
        } else {
            R.string.detail_original_message_show
        },
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = actionLabel) {
                    onEvent(TransactionDetailEvent.ToggleBody)
                }
                .semantics { stateDescription = stateLabel }
                .heightIn(min = spacing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.detail_original_message, raw.sender),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (state.isBodyExpanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
            )
        }
        if (state.isBodyExpanded) {
            Text(
                text = raw.body,
                style = MaterialTheme.typography.bodyMedium,
                // Monospace so a reference number can be compared character by
                // character against the provider's own message.
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(spacing.md),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMode(
    editor: TransactionEditor,
    onEvent: (TransactionDetailEvent) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    var showDatePicker by remember { mutableStateOf(false) }
    var channelExpanded by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = editor.amountText,
        onValueChange = { onEvent(TransactionDetailEvent.EditorChanged(editor.copy(amountText = it))) },
        label = { Text(stringResource(R.string.detail_field_amount)) },
        isError = editor.amountIsInvalid,
        supportingText = if (editor.amountIsInvalid) {
            { Text(stringResource(R.string.detail_amount_invalid)) }
        } else {
            null
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Direction.entries.forEach { direction ->
            FilterChip(
                selected = editor.direction == direction,
                onClick = {
                    onEvent(TransactionDetailEvent.EditorChanged(editor.copy(direction = direction)))
                },
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

    Row {
        FilterChip(
            selected = true,
            onClick = { channelExpanded = true },
            label = { Text(editor.channel.label()) },
        )
        DropdownMenu(expanded = channelExpanded, onDismissRequest = { channelExpanded = false }) {
            Channel.entries.forEach { channel ->
                DropdownMenuItem(
                    text = { Text(channel.label()) },
                    onClick = {
                        onEvent(TransactionDetailEvent.EditorChanged(editor.copy(channel = channel)))
                        channelExpanded = false
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = editor.counterpartyName,
        onValueChange = {
            onEvent(TransactionDetailEvent.EditorChanged(editor.copy(counterpartyName = it)))
        },
        label = { Text(stringResource(R.string.detail_field_counterparty)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = editor.counterpartyPhone,
        onValueChange = {
            onEvent(TransactionDetailEvent.EditorChanged(editor.copy(counterpartyPhone = it)))
        },
        label = { Text(stringResource(R.string.detail_field_phone)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Phone,
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = editor.note,
        onValueChange = { onEvent(TransactionDetailEvent.EditorChanged(editor.copy(note = it))) },
        label = { Text(stringResource(R.string.detail_field_note)) },
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedButton(
        onClick = { showDatePicker = true },
        modifier = Modifier.heightIn(min = spacing.touchTarget),
    ) {
        Text(rememberDayLabel(editor.occurredAt))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        TextButton(onClick = { onEvent(TransactionDetailEvent.CancelEdit) }) {
            Text(stringResource(R.string.action_cancel))
        }
        OutlinedButton(onClick = { onEvent(TransactionDetailEvent.SaveEdit) }) {
            Text(stringResource(R.string.action_save))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = editor.occurredAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            // The picker returns UTC midnight. Keep the original
                            // time of day: only the date was being changed.
                            val zone = ZoneId.systemDefault()
                            val timeOfDay = editor.occurredAt -
                                MezgebDates.startOfDay(editor.occurredAt, zone)
                            val newDay = MezgebDates.startOfDay(picked, zone)
                            onEvent(
                                TransactionDetailEvent.EditorChanged(
                                    editor.copy(occurredAt = newDay + timeOfDay),
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

@Composable
private fun DetailRow(label: String, value: String, valueIsAmount: Boolean = false) {
    val spacing = MezgebTheme.spacing
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // A name or a note may wrap in its share of the row. An amount must not:
            // at the largest system font a six-figure balance with "ETB" is wider than
            // that share, and a balance over two lines is misread. So an amount takes
            // the width it needs, and the label wraps instead (ROADMAP R24).
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (valueIsAmount) Modifier else Modifier.weight(1.4f),
            )
        }
        HorizontalDivider()
    }
}

@Preview(name = "Transaction detail", showBackground = true, heightDp = 900)
@Composable
private fun TransactionDetailPreview() {
    MezgebTheme {
        TransactionDetailScreen(
            state = TransactionDetailUiState(
                isLoading = false,
                transaction = Transaction(
                    id = "1",
                    rawMessageId = "raw1",
                    direction = Direction.IN,
                    amountMinor = 45_000,
                    currency = "ETB",
                    channel = Channel.TELEBIRR,
                    counterpartyName = "ABEBE",
                    counterpartyPhone = "+251900000001",
                    providerTxnId = "CJ4T5X9AB1",
                    balanceAfterMinor = 120_000,
                    occurredAt = 1_782_983_640_000L,
                    categoryId = "cat_sales",
                    note = null,
                    isReconciled = false,
                    reconciliationSessionId = null,
                    isDeleted = false,
                    editedAt = null,
                ),
                categoryName = "Sales",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
