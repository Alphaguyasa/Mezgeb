package et.alpha.mezgeb.ui.today

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import javax.inject.Inject

/**
 * Session 1 shell. Today's real state — totals, capture health, the recent list —
 * arrives in Session 5. What exists here is the MVVM shape every screen follows:
 * one [StateFlow] out, one [onEvent] in, nothing mutable escaping.
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val nowMillis: Long = 0L,
)

sealed interface TodayEvent {
    data object Refresh : TodayEvent
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: TodayEvent) {
        when (event) {
            TodayEvent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        _state.update { it.copy(isLoading = false, nowMillis = clock.millis()) }
    }
}
