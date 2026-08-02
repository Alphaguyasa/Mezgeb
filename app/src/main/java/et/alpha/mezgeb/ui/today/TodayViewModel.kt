package et.alpha.mezgeb.ui.today

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.capture.CaptureHealth
import et.alpha.mezgeb.capture.CaptureHealthMonitor
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.capture.vendor.BatteryOptimizationHelper
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.ui.common.TransactionUiModel
import et.alpha.mezgeb.ui.common.toUiModel
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/**
 * What the owner sees when she opens the app.
 *
 * @param isEmpty distinguishes "nothing has happened today" from "nothing has ever
 *   happened", because the two need different words: one is a quiet morning, the
 *   other means capture may never have worked.
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val totalInMinor: Long = 0L,
    val totalOutMinor: Long = 0L,
    val captureHealth: CaptureHealth = CaptureHealth.HEALTHY,
    val reviewQueueCount: Int = 0,
    val recent: List<TransactionUiModel> = emptyList(),
    val hasEverCaptured: Boolean = false,
    val errorMessage: ErrorKind? = null,
) {
    val netMinor: Long get() = totalInMinor - totalOutMinor
    val isEmpty: Boolean get() = recent.isEmpty()

    enum class ErrorKind { LOAD_FAILED }
}

sealed interface TodayEvent {
    /** Re-checks notification access and rolls the day over if it has changed. */
    data object Refresh : TodayEvent
}

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    rules: RuleRepository,
    private val captureHealth: CaptureHealthMonitor,
    private val access: CaptureAccess,
    private val battery: BatteryOptimizationHelper,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TodayUiState> = refreshes
        // The day's bounds are captured per refresh rather than once: an app left open
        // overnight would otherwise still be totalling yesterday.
        .flatMapLatest { todayFlow(rules) }
        .catch { emit(TodayUiState(isLoading = false, errorMessage = TodayUiState.ErrorKind.LOAD_FAILED)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = TodayUiState(),
        )

    private fun todayFlow(rules: RuleRepository) = run {
        val bounds = MezgebDates.dayBounds(clock.millis(), zone)

        val totals = combine(
            transactions.observeSum(Direction.IN, bounds.first, bounds.last),
            transactions.observeSum(Direction.OUT, bounds.first, bounds.last),
        ) { inMinor, outMinor -> inMinor to outMinor }

        val recent = combine(
            transactions.observeRecent(RECENT_LIMIT),
            rules.observeCategories(),
        ) { list, categories ->
            val namesById = categories.associate { it.id to it.name }
            list.map { it.toUiModel(categoryName = namesById[it.categoryId]) }
        }

        combine(
            totals,
            recent,
            transactions.observeUnmatchedCount(),
            captureHealth.observe(),
        ) { (inMinor, outMinor), rows, unmatched, health ->
            TodayUiState(
                isLoading = false,
                totalInMinor = inMinor,
                totalOutMinor = outMinor,
                captureHealth = health,
                reviewQueueCount = unmatched,
                recent = rows,
                // Anything at all in the ledger, today or not, means capture has worked
                // at least once. An empty Today then means a quiet day, not a dead app.
                hasEverCaptured = rows.isNotEmpty(),
            )
        }
    }

    fun onEvent(event: TodayEvent) {
        when (event) {
            TodayEvent.Refresh -> {
                captureHealth.refresh()
                refreshes.update { it + 1 }
            }
        }
    }

    /** The fix for the banner the user is looking at, not a generic settings screen. */
    fun healthFixIntent(health: CaptureHealth): Intent? = when (health) {
        CaptureHealth.NO_ACCESS -> access.settingsIntent()
        CaptureHealth.SILENT_TOO_LONG -> battery.settingsIntent()
        CaptureHealth.HEALTHY -> null
    }

    private companion object {
        const val RECENT_LIMIT = 10
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
