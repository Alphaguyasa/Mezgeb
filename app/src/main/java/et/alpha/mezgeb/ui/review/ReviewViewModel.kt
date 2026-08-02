package et.alpha.mezgeb.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.capture.BacklogReprocessor
import et.alpha.mezgeb.capture.ReprocessStatus
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.UnmatchedGroup
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ReviewUiState(
    val isLoading: Boolean = true,
    val groups: List<UnmatchedGroup> = emptyList(),
    val reprocess: ReprocessStatus = ReprocessStatus.Idle,
) {
    /**
     * An empty queue is the app working, not the app having nothing to show. The
     * screen says so instead of apologising.
     */
    val isEmpty: Boolean get() = groups.isEmpty()

    val totalWaiting: Int get() = groups.sumOf { it.messageCount }
}

sealed interface ReviewEvent {
    /** Try the whole backlog again, without teaching anything new. */
    data object Reprocess : ReviewEvent
}

/**
 * The queue of messages no rule could read, grouped by who sent them.
 *
 * Grouped because that is the unit of work: one sender uses one template, so teaching
 * one rule clears the whole group. A flat list of forty messages looks like forty
 * problems; the same messages under three senders look like three.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    transactions: TransactionRepository,
    private val reprocessor: BacklogReprocessor,
) : ViewModel() {

    val state: StateFlow<ReviewUiState> = combine(
        transactions.observeUnmatchedGroups(),
        reprocessor.observe(),
    ) { groups, reprocess ->
        ReviewUiState(isLoading = false, groups = groups, reprocess = reprocess)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ReviewUiState(),
    )

    fun onEvent(event: ReviewEvent) {
        when (event) {
            ReviewEvent.Reprocess -> reprocessor.start()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
