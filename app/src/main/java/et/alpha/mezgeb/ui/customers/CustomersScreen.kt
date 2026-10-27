package et.alpha.mezgeb.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.util.MoneyFormatter

@Composable
fun CustomersRoute(
    onOpenCustomer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CustomersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CustomersScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenCustomer = onOpenCustomer,
        modifier = modifier,
    )
}

@Composable
fun CustomersScreen(
    state: CustomersUiState,
    onEvent: (CustomersEvent) -> Unit,
    onOpenCustomer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.customers_save_failed)

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            snackbarHostState.showSnackbar(errorText)
            onEvent(CustomersEvent.ErrorShown)
        }
    }

    MezgebScaffold(
        title = stringResource(R.string.destination_customers),
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEvent(CustomersEvent.AddCustomerRequested) },
                // Named on the icon for the same reason as Today's FAB: Material reads
                // an extended FAB's name from its icon, not from its label.
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.customers_add),
                    )
                },
                text = { Text(stringResource(R.string.customers_add)) },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            OutstandingHeader(
                totalMinor = state.totalOutstandingMinor,
                customersInDebt = state.customersInDebt,
                modifier = Modifier.padding(spacing.screenGutter),
            )

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { onEvent(CustomersEvent.SearchChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenGutter),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onEvent(CustomersEvent.SearchChanged("")) }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.ledger_search_clear),
                            )
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.customers_search_hint)) },
            )

            when {
                state.isEmpty && state.isSearching -> EmptyState(
                    title = stringResource(R.string.customers_no_match_title),
                    body = stringResource(R.string.customers_no_match_body),
                )

                state.isEmpty && !state.isLoading -> EmptyState(
                    title = stringResource(R.string.placeholder_customers_title),
                    body = stringResource(R.string.placeholder_customers_body),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.customers, key = { it.customer.id }) { credit ->
                        CustomerRow(credit = credit, onClick = { onOpenCustomer(credit.customer.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (state.isAddingCustomer) {
        AddCustomerDialog(
            onDismiss = { onEvent(CustomersEvent.AddCustomerDismissed) },
            onConfirm = { name, phone -> onEvent(CustomersEvent.AddCustomer(name, phone)) },
        )
    }
}

/** The number a shop owner opens this screen for: how much is out there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutstandingHeader(
    totalMinor: Long,
    customersInDebt: Int,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.customers_outstanding_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The amount and "ETB" as a flow, like Today's headline (ROADMAP R26). As
            // one string, a six-figure total at the largest system font is wider than
            // the card and wraps, which can break inside the number. As two Texts,
            // only "ETB" moves under the amount. Read as one amount, as before.
            val spokenTotal = stringResource(
                R.string.amount_content_description,
                MoneyFormatter.formatUnsigned(totalMinor),
                MoneyFormatter.DEFAULT_CURRENCY,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.clearAndSetSemantics { contentDescription = spokenTotal },
            ) {
                Text(
                    text = MoneyFormatter.format(totalMinor, currency = null),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = MoneyFormatter.DEFAULT_CURRENCY,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = pluralStringResource(
                    R.plurals.customers_in_debt,
                    customersInDebt,
                    customersInDebt,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CustomerRow(credit: CustomerCredit, onClick: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = spacing.touchTarget)
            .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = credit.customer.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val days = credit.daysOutstanding
            if (days != null) {
                Text(
                    text = pluralStringResource(R.plurals.customers_days_outstanding, days, days),
                    style = MaterialTheme.typography.bodySmall,
                    // Overdue is stated in words as well as colour: a red number alone
                    // is invisible to a colour-blind reader and to a bright screen.
                    color = if (credit.isOverdue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            // The minus stays on screen, where it lines the column up by eye. TalkBack
            // would read it as "minus 20", so it hears the amount alone, and the "You
            // owe them" line under it says which way the money is owed.
            val spokenBalance = stringResource(
                R.string.amount_content_description,
                MoneyFormatter.formatUnsigned(credit.balanceMinor),
                MoneyFormatter.DEFAULT_CURRENCY,
            )
            Text(
                text = MoneyFormatter.format(credit.balanceMinor),
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    credit.isOverdue -> MaterialTheme.colorScheme.error
                    credit.shopOwesCustomer -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.clearAndSetSemantics { contentDescription = spokenBalance },
            )
            if (credit.isOverdue) {
                Text(
                    text = stringResource(R.string.customers_overdue),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (credit.shopOwesCustomer) {
                Text(
                    text = stringResource(R.string.customers_shop_owes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddCustomerDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customers_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MezgebTheme.spacing.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.customers_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
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
                onClick = { onConfirm(name, phone) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview(name = "Customers", showBackground = true, heightDp = 720)
@Composable
private fun CustomersScreenPreview() {
    MezgebTheme {
        CustomersScreen(
            state = CustomersUiState(
                isLoading = false,
                totalOutstandingMinor = 1_245_000,
                customersInDebt = 3,
                customers = listOf(
                    previewCredit("1", "Abebe Bekele", 45_000, days = 41, overdue = true),
                    previewCredit("2", "Chaltu Diriba", 12_500, days = 6, overdue = false),
                    previewCredit("3", "Kebede Tesfaye", -2_000, days = null, overdue = false),
                ),
            ),
            onEvent = {},
            onOpenCustomer = {},
        )
    }
}

private fun previewCredit(
    id: String,
    name: String,
    balanceMinor: Long,
    days: Int?,
    overdue: Boolean,
) = CustomerCredit(
    customer = Customer(id, name, phone = null, note = null, createdAt = 0L),
    balanceMinor = balanceMinor,
    oldestUnpaidAt = null,
    daysOutstanding = days,
    isOverdue = overdue,
)
