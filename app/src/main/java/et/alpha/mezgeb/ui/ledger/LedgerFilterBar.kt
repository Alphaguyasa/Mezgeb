package et.alpha.mezgeb.ui.ledger

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.label
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction

/**
 * Search and filters, pinned above the list.
 *
 * Every filter is a chip that reads as its current value rather than as its name —
 * "telebirr" not "Channel" — so the bar states what is being shown without the user
 * having to open anything to find out.
 */
@Composable
fun LedgerFilterBar(
    state: LedgerUiState,
    onEvent: (LedgerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = spacing.xxs) {
        Column(
            modifier = Modifier.padding(
                start = spacing.screenGutter,
                end = spacing.screenGutter,
                top = spacing.sm,
                bottom = spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { onEvent(LedgerEvent.SearchChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onEvent(LedgerEvent.SearchChanged("")) }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.ledger_search_clear),
                            )
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.ledger_search_hint)) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
            )

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                DateRangeChip(state.dateRange, onEvent)
                DirectionChip(state.filter, onEvent)
                ChannelChipFilter(state.filter, onEvent)
                CategoryChip(state.filter, state.categories, onEvent)
                ReconciledChip(state.filter, onEvent)

                if (state.isFiltered) {
                    FilterChip(
                        selected = false,
                        onClick = { onEvent(LedgerEvent.ClearFilters) },
                        label = { Text(stringResource(R.string.ledger_filter_clear)) },
                        leadingIcon = { Icon(Icons.Filled.Clear, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateRangeChip(range: DateRange, onEvent: (LedgerEvent) -> Unit) {
    MenuChip(
        selected = range != DateRange.ALL,
        label = stringResource(range.labelRes()),
    ) { dismiss ->
        DateRange.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(stringResource(option.labelRes())) },
                onClick = {
                    onEvent(LedgerEvent.DateRangeChanged(option))
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun DirectionChip(filter: TransactionFilter, onEvent: (LedgerEvent) -> Unit) {
    val label = when (filter.direction) {
        Direction.IN -> stringResource(R.string.direction_in)
        Direction.OUT -> stringResource(R.string.direction_out)
        null -> stringResource(R.string.ledger_filter_direction_any)
    }
    MenuChip(selected = filter.direction != null, label = label) { dismiss ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ledger_filter_direction_any)) },
            onClick = {
                onEvent(LedgerEvent.FilterChanged(filter.copy(direction = null)))
                dismiss()
            },
        )
        Direction.entries.forEach { direction ->
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            when (direction) {
                                Direction.IN -> R.string.direction_in
                                Direction.OUT -> R.string.direction_out
                            },
                        ),
                    )
                },
                onClick = {
                    onEvent(LedgerEvent.FilterChanged(filter.copy(direction = direction)))
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun ChannelChipFilter(filter: TransactionFilter, onEvent: (LedgerEvent) -> Unit) {
    val label = filter.channel?.label() ?: stringResource(R.string.ledger_filter_channel_any)
    MenuChip(selected = filter.channel != null, label = label) { dismiss ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ledger_filter_channel_any)) },
            onClick = {
                onEvent(LedgerEvent.FilterChanged(filter.copy(channel = null)))
                dismiss()
            },
        )
        Channel.entries.forEach { channel ->
            DropdownMenuItem(
                text = { Text(channel.label()) },
                onClick = {
                    onEvent(LedgerEvent.FilterChanged(filter.copy(channel = channel)))
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun CategoryChip(
    filter: TransactionFilter,
    categories: List<Category>,
    onEvent: (LedgerEvent) -> Unit,
) {
    val selected = categories.firstOrNull { it.id == filter.categoryId }
    val label = selected?.name ?: stringResource(R.string.ledger_filter_category_any)
    MenuChip(selected = filter.categoryId != null, label = label) { dismiss ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ledger_filter_category_any)) },
            onClick = {
                onEvent(LedgerEvent.FilterChanged(filter.copy(categoryId = null)))
                dismiss()
            },
        )
        categories.forEach { category ->
            DropdownMenuItem(
                text = { Text(category.name) },
                onClick = {
                    onEvent(LedgerEvent.FilterChanged(filter.copy(categoryId = category.id)))
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun ReconciledChip(filter: TransactionFilter, onEvent: (LedgerEvent) -> Unit) {
    FilterChip(
        selected = filter.isReconciled == true,
        onClick = {
            // Three states would need a third tap nobody discovers. Off means "all".
            val next = if (filter.isReconciled == true) null else true
            onEvent(LedgerEvent.FilterChanged(filter.copy(isReconciled = next)))
        },
        label = { Text(stringResource(R.string.ledger_filter_reconciled)) },
    )
}

/** A chip that opens a menu, since Material 3 has no first-class filter dropdown. */
@Composable
private fun MenuChip(
    selected: Boolean,
    label: String,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { expanded = true },
            label = { Text(label) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

private fun DateRange.labelRes(): Int = when (this) {
    DateRange.ALL -> R.string.ledger_filter_date_all
    DateRange.TODAY -> R.string.ledger_filter_date_today
    DateRange.LAST_7_DAYS -> R.string.ledger_filter_date_7_days
    DateRange.LAST_30_DAYS -> R.string.ledger_filter_date_30_days
}

@Preview(name = "LedgerFilterBar", showBackground = true)
@Composable
private fun LedgerFilterBarPreview() {
    MezgebTheme {
        LedgerFilterBar(
            state = LedgerUiState(
                isLoading = false,
                searchQuery = "abebe",
                dateRange = DateRange.LAST_7_DAYS,
                filter = TransactionFilter(direction = Direction.IN),
            ),
            onEvent = {},
        )
    }
}
