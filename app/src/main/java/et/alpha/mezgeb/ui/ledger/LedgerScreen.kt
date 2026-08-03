package et.alpha.mezgeb.ui.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.ui.common.DayHeader
import et.alpha.mezgeb.ui.common.TransactionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun LedgerRoute(
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LedgerScreen(
        state = state,
        items = viewModel.items,
        onEvent = viewModel::onEvent,
        onOpenTransaction = onOpenTransaction,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    state: LedgerUiState,
    items: Flow<PagingData<LedgerItem>>,
    onEvent: (LedgerEvent) -> Unit,
    onOpenTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = items.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }

    // A re-parse usually changes nothing visible. Saying so is the difference between
    // "that did something" and "did that do anything?".
    val reparse = state.lastReparse
    val reparseMessage = when {
        reparse == null -> null
        reparse.changed > 0 -> pluralStringResource(
            R.plurals.ledger_reparse_changed,
            reparse.recorded,
            reparse.recorded,
        )
        else -> stringResource(R.string.ledger_reparse_unchanged)
    }
    LaunchedEffect(reparse) {
        if (reparseMessage != null) {
            snackbarHostState.showSnackbar(reparseMessage)
            onEvent(LedgerEvent.ReparseResultShown)
        }
    }

    MezgebScaffold(
        title = stringResource(R.string.destination_ledger),
        modifier = modifier,
        snackbarHostState = snackbarHostState,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            LedgerFilterBar(state = state, onEvent = onEvent)

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { onEvent(LedgerEvent.Refresh) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LedgerList(
                    rows = rows,
                    isFiltered = state.isFiltered,
                    onOpenTransaction = onOpenTransaction,
                    onClearFilters = { onEvent(LedgerEvent.ClearFilters) },
                )
            }
        }
    }
}

@Composable
private fun LedgerList(
    rows: LazyPagingItems<LedgerItem>,
    isFiltered: Boolean,
    onOpenTransaction: (String) -> Unit,
    onClearFilters: () -> Unit,
) {
    val isLoadingFirstPage = rows.loadState.refresh is LoadState.Loading
    val hasFailed = rows.loadState.refresh is LoadState.Error
    val isEmpty = rows.itemCount == 0 && !isLoadingFirstPage && !hasFailed

    when {
        rows.itemCount == 0 && isLoadingFirstPage -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        // Checked before either empty state, and deliberately so. A read that failed
        // and a ledger with nothing in it are indistinguishable by row count, and
        // telling a shop owner their ledger is empty when the query threw is the worst
        // thing this screen could say.
        rows.itemCount == 0 && hasFailed -> EmptyState(
            title = stringResource(R.string.ledger_error_title),
            body = stringResource(R.string.ledger_error_body),
            actionLabel = stringResource(R.string.action_try_again),
            onAction = { rows.retry() },
        )

        // Two empty states, because they mean different things and only one of them is
        // something the user did.
        isEmpty && isFiltered -> EmptyState(
            title = stringResource(R.string.ledger_empty_filtered_title),
            body = stringResource(R.string.ledger_empty_filtered_body),
            actionLabel = stringResource(R.string.ledger_filter_clear),
            onAction = onClearFilters,
        )

        isEmpty -> EmptyState(
            title = stringResource(R.string.placeholder_ledger_title),
            body = stringResource(R.string.placeholder_ledger_body),
        )

        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(
                count = rows.itemCount,
                key = { index ->
                    when (val item = rows.peek(index)) {
                        is LedgerItem.Header -> "day_${item.dayEpochMillis}"
                        is LedgerItem.Row -> item.transaction.id
                        null -> "placeholder_$index"
                    }
                },
            ) { index ->
                when (val item = rows[index]) {
                    is LedgerItem.Header -> DayHeader(
                        dayEpochMillis = item.dayEpochMillis,
                        inMinor = item.inMinor,
                        outMinor = item.outMinor,
                    )

                    is LedgerItem.Row -> {
                        TransactionRow(
                            transaction = item.transaction,
                            onClick = { onOpenTransaction(item.transaction.id) },
                        )
                        HorizontalDivider()
                    }

                    null -> Unit
                }
            }
        }
    }
}

@Preview(name = "Ledger (empty)", showBackground = true, heightDp = 800)
@Composable
private fun LedgerScreenPreview() {
    MezgebTheme {
        LedgerScreen(
            state = LedgerUiState(isLoading = false),
            items = flowOf(PagingData.empty()),
            onEvent = {},
            onOpenTransaction = {},
        )
    }
}
