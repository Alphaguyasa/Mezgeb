package et.alpha.mezgeb.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.capture.ReparseSummary
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.common.toUiModel
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/** One entry in the ledger list: a day header, or a transaction under it. */
sealed interface LedgerItem {

    data class Header(
        val dayEpochMillis: Long,
        val inMinor: Long,
        val outMinor: Long,
    ) : LedgerItem

    data class Row(val transaction: TransactionUiModel) : LedgerItem
}

/** The date filter, as the four choices a shop actually makes. */
enum class DateRange {
    ALL,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
}

data class LedgerUiState(
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val dateRange: DateRange = DateRange.ALL,
    val filter: TransactionFilter = TransactionFilter(),
    val categories: List<Category> = emptyList(),
    val isRefreshing: Boolean = false,
    /** Set after a pull-to-refresh so the user is told what changed, then cleared. */
    val lastReparse: ReparseSummary? = null,
) {
    /** True when the list being empty is the filter's doing rather than the ledger's. */
    val isFiltered: Boolean get() = filter.isActive
}

sealed interface LedgerEvent {
    data class SearchChanged(val query: String) : LedgerEvent
    data class DateRangeChanged(val range: DateRange) : LedgerEvent
    data class FilterChanged(val filter: TransactionFilter) : LedgerEvent
    data object ClearFilters : LedgerEvent

    /** Pull to refresh. Re-runs the parser over the review queue. */
    data object Refresh : LedgerEvent
    data object ReparseResultShown : LedgerEvent
}

/**
 * The ledger.
 *
 * **Deviation from .md's one-StateFlow rule, on purpose.** `PagingData` is a
 * stream of diffs that must be collected exactly once and cached in the ViewModel
 * scope; putting it inside the UI state would make every unrelated state change
 * (a keystroke in the search box) look like a new list to Paging. So there are two
 * outputs: [state] for everything the screen controls, and [items] for the list.
 * Events still go one way, through [onEvent].
 */
@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    rules: RuleRepository,
    private val coordinator: CaptureCoordinator,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {

    private val _state = MutableStateFlow(LedgerUiState())
    val state: StateFlow<LedgerUiState> = _state

    private val searchInput = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    private val activeFilter: StateFlow<TransactionFilter> = combine(
        _state.map { it.filter }.distinctUntilChanged(),
        // Debounced so that typing a customer's name does not run one query per letter
        // against a ledger with thousands of rows.
        searchInput.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged(),
    ) { filter, query ->
        filter.copy(query = query.takeIf { it.isNotBlank() })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TransactionFilter())

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<LedgerItem>> = activeFilter
        .flatMapLatest { filter ->
            combine(
                transactions.pagedTransactions(filter),
                transactions.observeDailyTotals(filter, zone),
                rules.observeCategories(),
            ) { paging, dayTotals, categories ->
                val totalsByDay = dayTotals.associateBy { it.dayEpochMillis }
                val categoryNames = categories.associate { it.id to it.name }

                paging
                    .map<_, LedgerItem> { transaction ->
                        LedgerItem.Row(
                            transaction.toUiModel(categoryNames[transaction.categoryId]),
                        )
                    }
                    .insertSeparators { before, after ->
                        val next = (after as? LedgerItem.Row) ?: return@insertSeparators null
                        val previous = before as? LedgerItem.Row
                        val day = startOfDay(next)
                        // A header goes above the first row of each day, and nowhere else.
                        if (previous != null && startOfDay(previous) == day) {
                            return@insertSeparators null
                        }
                        val totals = totalsByDay[day]
                        LedgerItem.Header(
                            dayEpochMillis = day,
                            inMinor = totals?.inMinor ?: 0L,
                            outMinor = totals?.outMinor ?: 0L,
                        )
                    }
            }
        }
        .cachedIn(viewModelScope)

    init {
        // Observed rather than read once: this screen outlives a trip to settings,
        // where a category can be added, and a filter bar that cannot offer a category
        // the ledger is already using would be quietly wrong.
        viewModelScope.launch {
            rules.observeCategories().collect { categories ->
                _state.update { it.copy(isLoading = false, categories = categories) }
            }
        }
    }

    fun onEvent(event: LedgerEvent) {
        when (event) {
            is LedgerEvent.SearchChanged -> {
                _state.update { it.copy(searchQuery = event.query) }
                searchInput.value = event.query
            }

            is LedgerEvent.DateRangeChanged -> _state.update { current ->
                current.copy(
                    dateRange = event.range,
                    filter = current.filter.withDateRange(event.range),
                )
            }

            is LedgerEvent.FilterChanged -> _state.update { it.copy(filter = event.filter) }

            LedgerEvent.ClearFilters -> {
                searchInput.value = ""
                _state.update {
                    it.copy(
                        searchQuery = "",
                        dateRange = DateRange.ALL,
                        filter = TransactionFilter(),
                    )
                }
            }

            LedgerEvent.Refresh -> refresh()

            LedgerEvent.ReparseResultShown -> _state.update { it.copy(lastReparse = null) }
        }
    }

    /**
     * Pull to refresh means "try again on the things you could not read", not "fetch"
     * — there is nothing to fetch. A rule taught since the last look may now match
     * messages that are still sitting in the review queue.
     */
    private fun refresh() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val summary = runCatching { coordinator.reparseUnmatched() }.getOrNull()
            _state.update { it.copy(isRefreshing = false, lastReparse = summary) }
        }
    }

    private fun startOfDay(row: LedgerItem.Row): Long =
        MezgebDates.startOfDay(row.transaction.occurredAt, zone)

    private fun TransactionFilter.withDateRange(range: DateRange): TransactionFilter {
        val now = clock.millis()
        val today = MezgebDates.dayBounds(now, zone)
        return when (range) {
            DateRange.ALL -> copy(from = null, to = null)
            DateRange.TODAY -> copy(from = today.first, to = today.last)
            DateRange.LAST_7_DAYS -> copy(
                from = today.first - 6 * MezgebDates.DAY_MILLIS,
                to = today.last,
            )
            DateRange.LAST_30_DAYS -> copy(
                from = today.first - 29 * MezgebDates.DAY_MILLIS,
                to = today.last,
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
