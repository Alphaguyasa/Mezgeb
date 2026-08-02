package et.alpha.mezgeb.ui.customers

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.credit.ReminderMessage
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.ui.common.rememberDayLabel
import et.alpha.mezgeb.util.MoneyFormatter

@Composable
fun CustomerDetailRoute(
    onBack: () -> Unit,
    onAddCredit: (String) -> Unit,
    onRecordRepayment: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomerDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultTemplate = stringResource(R.string.credit_reminder_default_template)

    // Archiving takes the customer off this list; there is nothing left to look at.
    LaunchedEffect(state.isArchived) {
        if (state.isArchived) onBack()
    }

    CustomerDetailScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onAddCredit = { state.credit?.let { onAddCredit(it.customer.id) } },
        onRecordRepayment = { state.credit?.let { onRecordRepayment(it.customer.id) } },
        onSendReminder = {
            val phone = viewModel.customerPhone()
            val message = viewModel.reminderMessage(defaultTemplate)
            if (phone != null && message != null) {
                context.startSafely(ReminderMessage.intent(phone, message))
            }
        },
        modifier = modifier,
    )
}

@Composable
fun CustomerDetailScreen(
    state: CustomerDetailUiState,
    onEvent: (CustomerDetailEvent) -> Unit,
    onBack: () -> Unit,
    onAddCredit: () -> Unit,
    onRecordRepayment: () -> Unit,
    onSendReminder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val credit = state.credit

    MezgebScaffold(
        title = credit?.customer?.name ?: stringResource(R.string.destination_customers),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
        actions = {
            if (credit != null && state.editor == null) {
                IconButton(onClick = { onEvent(CustomerDetailEvent.StartEdit) }) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = stringResource(R.string.credit_edit_customer),
                    )
                }
            }
        },
    ) { inner ->
        if (credit == null) {
            if (!state.isLoading) {
                Text(
                    text = stringResource(R.string.credit_customer_not_found),
                    modifier = Modifier.padding(inner).padding(spacing.screenGutter),
                )
            }
            return@MezgebScaffold
        }

        LazyColumn(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            item("balance") {
                BalanceHeadline(
                    credit = credit,
                    modifier = Modifier.padding(spacing.screenGutter),
                )
            }

            item("actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screenGutter),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    Button(
                        onClick = onAddCredit,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.credit_add_sale))
                    }
                    OutlinedButton(
                        onClick = onRecordRepayment,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.credit_record_repayment))
                    }
                }
            }

            if (state.canRemind) {
                item("remind") {
                    OutlinedButton(
                        onClick = onSendReminder,
                        modifier = Modifier
                            .padding(
                                horizontal = spacing.screenGutter,
                                vertical = spacing.sm,
                            )
                            .heightIn(min = spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.credit_send_reminder))
                    }
                }
            }

            if (state.isEmpty) {
                item("empty") {
                    EmptyState(
                        title = stringResource(R.string.credit_statement_empty_title),
                        body = stringResource(R.string.credit_statement_empty_body),
                    )
                }
            } else {
                item("statement_heading") {
                    Text(
                        text = stringResource(R.string.credit_statement_heading),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(
                            start = spacing.screenGutter,
                            end = spacing.screenGutter,
                            top = spacing.lg,
                            bottom = spacing.sm,
                        ),
                    )
                }
                items(state.statement, key = { it.entry.id }) { line ->
                    StatementRow(line = line, onUnlink = { onEvent(CustomerDetailEvent.UnlinkEntry(it)) })
                    HorizontalDivider()
                }
            }

            item("archive") {
                TextButton(
                    onClick = { onEvent(CustomerDetailEvent.RequestArchive) },
                    modifier = Modifier
                        .padding(spacing.screenGutter)
                        .heightIn(min = spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.credit_archive))
                }
            }
        }
    }

    if (state.isConfirmingArchive) {
        AlertDialog(
            onDismissRequest = { onEvent(CustomerDetailEvent.DismissArchive) },
            title = { Text(stringResource(R.string.credit_archive_title)) },
            text = { Text(stringResource(R.string.credit_archive_body)) },
            confirmButton = {
                TextButton(onClick = { onEvent(CustomerDetailEvent.ConfirmArchive) }) {
                    Text(stringResource(R.string.credit_archive))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(CustomerDetailEvent.DismissArchive) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.editor?.let { editor ->
        AlertDialog(
            onDismissRequest = { onEvent(CustomerDetailEvent.CancelEdit) },
            title = { Text(stringResource(R.string.credit_edit_customer)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MezgebTheme.spacing.sm)) {
                    OutlinedTextField(
                        value = editor.name,
                        onValueChange = {
                            onEvent(CustomerDetailEvent.EditorChanged(editor.copy(name = it)))
                        },
                        label = { Text(stringResource(R.string.customers_name)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editor.phone,
                        onValueChange = {
                            onEvent(CustomerDetailEvent.EditorChanged(editor.copy(phone = it)))
                        },
                        label = { Text(stringResource(R.string.customers_phone)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                        ),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onEvent(CustomerDetailEvent.SaveEdit) },
                    enabled = editor.name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(CustomerDetailEvent.CancelEdit) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun BalanceHeadline(credit: CustomerCredit, modifier: Modifier = Modifier) {
    val spacing = MezgebTheme.spacing
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(
                    if (credit.shopOwesCustomer) {
                        R.string.credit_balance_shop_owes
                    } else {
                        R.string.credit_balance_owes
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = MoneyFormatter.format(
                    if (credit.shopOwesCustomer) -credit.balanceMinor else credit.balanceMinor,
                ),
                style = MaterialTheme.typography.displaySmall,
                color = if (credit.isOverdue) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            credit.daysOutstanding?.let { days ->
                Text(
                    text = stringResource(R.string.customers_days_outstanding, days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (credit.isOverdue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** One line of the statement: what happened, and the balance after it. */
@Composable
private fun StatementRow(line: CreditStatementLine, onUnlink: (String) -> Unit) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when (line.entry.kind) {
                        CreditKind.CREDIT_GIVEN -> R.string.credit_kind_given
                        CreditKind.REPAYMENT -> R.string.credit_kind_repayment
                        CreditKind.ADJUSTMENT -> R.string.credit_kind_adjustment
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = rememberDayLabel(line.entry.occurredAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line.entry.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (line.entry.linkedTransactionId != null) {
                TextButton(onClick = { onUnlink(line.entry.id) }) {
                    Text(stringResource(R.string.credit_linked_unlink))
                }
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = when (line.entry.kind) {
                    CreditKind.REPAYMENT -> "−" + MoneyFormatter.formatUnsigned(line.entry.amountMinor)
                    else -> "+" + MoneyFormatter.formatUnsigned(line.entry.amountMinor)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = MoneyFormatter.format(line.runningBalanceMinor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

@Preview(name = "Customer detail", showBackground = true, heightDp = 800)
@Composable
private fun CustomerDetailPreview() {
    MezgebTheme {
        CustomerDetailScreen(
            state = CustomerDetailUiState(
                isLoading = false,
                credit = CustomerCredit(
                    customer = Customer("1", "Abebe Bekele", "+251911000001", null, 0L),
                    balanceMinor = 45_000,
                    oldestUnpaidAt = 1_782_900_000_000L,
                    daysOutstanding = 41,
                    isOverdue = true,
                ),
                statement = listOf(
                    statementLine("e1", CreditKind.CREDIT_GIVEN, 50_000, 50_000),
                    statementLine("e2", CreditKind.REPAYMENT, 5_000, 45_000),
                ),
            ),
            onEvent = {},
            onBack = {},
            onAddCredit = {},
            onRecordRepayment = {},
            onSendReminder = {},
        )
    }
}

private fun statementLine(
    id: String,
    kind: CreditKind,
    amountMinor: Long,
    runningMinor: Long,
) = CreditStatementLine(
    entry = CreditEntry(
        id = id,
        customerId = "1",
        kind = kind,
        amountMinor = amountMinor,
        occurredAt = 1_782_900_000_000L,
        note = null,
        linkedTransactionId = null,
    ),
    runningBalanceMinor = runningMinor,
)
