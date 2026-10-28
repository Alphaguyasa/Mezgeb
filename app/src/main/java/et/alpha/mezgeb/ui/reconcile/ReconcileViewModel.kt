package et.alpha.mezgeb.ui.reconcile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.LedgerChanges
import et.alpha.mezgeb.data.repo.ReconciliationRepository
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.domain.reconcile.PeriodReconciliation
import et.alpha.mezgeb.domain.reconcile.ReconcilePeriodUseCase
import et.alpha.mezgeb.parser.normalize.AmountNormalizer
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/**
 * The longest note a closed period keeps. A sentence or two is enough for "gave 200
 * change from my own pocket", and a short cell keeps an exported period readable.
 */
internal const val NOTE_MAX_LENGTH = 200

/** The periods a shop actually reconciles. */
enum class PeriodChoice {
    TODAY,
    THIS_WEEK,
    CUSTOM,
}

data class ReconcileUiState(
    val isLoading: Boolean = true,
    val openSession: ReconciliationSession? = null,
    val reconciliation: PeriodReconciliation? = null,
    val closedSessions: List<ReconciliationSession> = emptyList(),
    val periodChoice: PeriodChoice = PeriodChoice.TODAY,
    val customRange: LongRange? = null,
    /** What the user has typed as the expected total, before it is stored. */
    val expectedInput: String = "",
    val expectedIsInvalid: Boolean = false,
    /** The owner's own words on why the period has a gap, kept with it when it closes. */
    val noteInput: String = "",
    val isBusy: Boolean = false,
    /** The closed period being read, if one is open on screen. */
    val viewingSnapshot: ReconciliationSession? = null,
    val justClosedId: String? = null,
) {
    val hasOpenPeriod: Boolean get() = openSession != null
    val isBalanced: Boolean get() = reconciliation?.isBalanced == true

    /** A note explains a gap, so it is asked for only when there is one. */
    val canAddNote: Boolean get() = reconciliation?.gapMinor != null && !isBalanced
    val canClose: Boolean get() = openSession != null && reconciliation?.gapMinor != null && !isBusy
}

sealed interface ReconcileEvent {
    data class PeriodChosen(val choice: PeriodChoice) : ReconcileEvent
    data class CustomRangeChosen(val from: Long, val to: Long) : ReconcileEvent
    data object OpenPeriod : ReconcileEvent

    data class ExpectedChanged(val text: String) : ReconcileEvent
    data object SaveExpected : ReconcileEvent

    data class NoteChanged(val text: String) : ReconcileEvent

    data object ClosePeriod : ReconcileEvent
    data class ViewSnapshot(val session: ReconciliationSession) : ReconcileEvent
    data object DismissSnapshot : ReconcileEvent
    data object CloseAcknowledged : ReconcileEvent
}

/**
 * Reconciliation: what the shop thinks it took, against what the app recorded.
 *
 * The recomputation is deliberately eager — every figure is recalculated whenever the
 * open session or the ledger changes, rather than cached — because the whole value of
 * the screen is that the number is current. A stale gap is worse than no gap. Closing
 * computes the figures once more, so the snapshot never rests on what was last shown.
 */
@HiltViewModel
class ReconcileViewModel @Inject constructor(
    private val sessions: ReconciliationRepository,
    private val reconcile: ReconcilePeriodUseCase,
    private val ledgerChanges: LedgerChanges,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: ZoneId,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val recomputes = MutableStateFlow(0)

    /**
     * The open session and its figures, recomputed when the session changes, when
     * anything the figures read is written (a payment captured while this screen is
     * open), or on request. Local UI state is deliberately not a trigger: typing a note
     * or an expected total must not re-run several queries per keystroke.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val computed: Flow<Computed> = combine(
        sessions.observeOpenSession(),
        ledgerChanges.observe(),
        recomputes,
    ) { open, _, _ -> open }
        .mapLatest { open ->
            // A period with no session has nothing to reconcile; the picker is shown
            // instead, so there is nothing to compute either.
            Computed(open, open?.let { figuresFor(it) })
        }

    val state: StateFlow<ReconcileUiState> = combine(
        computed,
        sessions.observeClosedSessions(),
        local,
    ) { (open, figures), closed, localState ->
        ReconcileUiState(
            isLoading = false,
            openSession = open,
            reconciliation = figures,
            closedSessions = closed,
            periodChoice = localState.periodChoice,
            customRange = localState.customRange,
            expectedInput = localState.expectedInput
                ?: open?.expectedTotalMinor?.let { MoneyFormatter.formatPlain(it) }
                ?: "",
            expectedIsInvalid = localState.expectedIsInvalid,
            noteInput = localState.noteInput,
            isBusy = localState.isBusy,
            viewingSnapshot = localState.viewingSnapshot,
            justClosedId = localState.justClosedId,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ReconcileUiState(),
    )

    fun onEvent(event: ReconcileEvent) {
        when (event) {
            is ReconcileEvent.PeriodChosen -> local.update { it.copy(periodChoice = event.choice) }

            is ReconcileEvent.CustomRangeChosen -> local.update {
                it.copy(
                    periodChoice = PeriodChoice.CUSTOM,
                    customRange = event.from..event.to,
                )
            }

            ReconcileEvent.OpenPeriod -> openPeriod()

            is ReconcileEvent.ExpectedChanged -> local.update {
                it.copy(expectedInput = event.text, expectedIsInvalid = false)
            }

            ReconcileEvent.SaveExpected -> saveExpected()

            is ReconcileEvent.NoteChanged -> local.update {
                it.copy(noteInput = event.text.take(NOTE_MAX_LENGTH))
            }

            ReconcileEvent.ClosePeriod -> closePeriod()

            is ReconcileEvent.ViewSnapshot -> local.update {
                it.copy(viewingSnapshot = event.session)
            }

            ReconcileEvent.DismissSnapshot -> local.update { it.copy(viewingSnapshot = null) }
            ReconcileEvent.CloseAcknowledged -> local.update { it.copy(justClosedId = null) }
        }
    }

    private fun openPeriod() {
        val range = rangeFor(local.value.periodChoice, local.value.customRange) ?: return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            sessions.openSession(
                ReconciliationSession(
                    id = ids.newId(),
                    periodStart = range.first,
                    periodEnd = range.last,
                    openedAt = clock.millis(),
                    expectedTotalMinor = null,
                    // Everything else is computed on demand until the period closes.
                    capturedInMinor = 0,
                    capturedOutMinor = 0,
                    cashRecordedMinor = 0,
                    creditGivenMinor = 0,
                    creditRepaidMinor = 0,
                    status = SessionStatus.OPEN,
                    closedAt = null,
                    note = null,
                ),
            )
            local.update { it.copy(isBusy = false, expectedInput = null) }
        }
    }

    private fun saveExpected() {
        val open = state.value.openSession ?: return
        val text = local.value.expectedInput.orEmpty()
        val minor = AmountNormalizer.toMinor(text)
        if (minor == null || minor < 0) {
            local.update { it.copy(expectedIsInvalid = true) }
            return
        }

        viewModelScope.launch {
            sessions.update(open.copy(expectedTotalMinor = minor))
            recomputes.update { it + 1 }
        }
    }

    /**
     * Closing snapshots every figure into the row.
     *
     * From here the numbers are history: editing a transaction inside this period is
     * allowed and changes nothing here. It shows up in the *next* period's "edited
     * since" explanation instead, which is the honest place for it.
     *
     * The note is kept only when the period closes with a gap. The field is hidden on
     * a balanced period, and a note typed before the gap closed would otherwise be
     * saved where the owner can no longer see it.
     */
    private fun closePeriod() {
        if (local.value.isBusy) return
        val shown = state.value.openSession ?: return
        val typedNote = local.value.noteInput.trim()
        local.update { it.copy(isBusy = true) }

        viewModelScope.launch {
            // The figures are computed again here rather than taken from the screen.
            // What the screen shows can lag a payment captured a moment ago, and a
            // snapshot saved from it could close a short period as balanced.
            val open = sessions.getOpenSession()?.takeIf { it.id == shown.id }
            val figures = open?.let { figuresFor(it) }
            if (open == null || figures == null || figures.gapMinor == null) {
                local.update { it.copy(isBusy = false) }
                return@launch
            }
            val note = typedNote.takeIf { it.isNotEmpty() && !figures.isBalanced }

            sessions.closeSession(
                open.copy(
                    expectedTotalMinor = figures.expectedTotalMinor,
                    capturedInMinor = figures.capturedInMinor,
                    capturedOutMinor = figures.capturedOutMinor,
                    cashRecordedMinor = figures.cashRecordedMinor,
                    creditGivenMinor = figures.creditGivenMinor,
                    creditRepaidMinor = figures.creditRepaidMinor,
                    status = SessionStatus.CLOSED,
                    closedAt = clock.millis(),
                    note = note,
                ),
            )
            local.update {
                it.copy(
                    isBusy = false,
                    justClosedId = open.id,
                    expectedInput = null,
                    noteInput = "",
                )
            }
        }
    }

    private suspend fun figuresFor(session: ReconciliationSession): PeriodReconciliation =
        reconcile(session.periodStart, session.periodEnd, openedAt = session.openedAt)

    private fun rangeFor(choice: PeriodChoice, custom: LongRange?): LongRange? {
        val now = clock.millis()
        return when (choice) {
            PeriodChoice.TODAY -> MezgebDates.dayBounds(now, zone)
            PeriodChoice.THIS_WEEK -> {
                val today = MezgebDates.dayBounds(now, zone)
                (today.first - 6 * MezgebDates.DAY_MILLIS)..today.last
            }
            // Without a chosen range there is no period, and opening one anyway would
            // invent a boundary the user never agreed to.
            PeriodChoice.CUSTOM -> custom
        }
    }

    private data class Computed(
        val openSession: ReconciliationSession?,
        val reconciliation: PeriodReconciliation?,
    )

    /** Null [expectedInput] means "show whatever the session holds". */
    private data class LocalState(
        val periodChoice: PeriodChoice = PeriodChoice.TODAY,
        val customRange: LongRange? = null,
        val expectedInput: String? = null,
        val expectedIsInvalid: Boolean = false,
        val noteInput: String = "",
        val isBusy: Boolean = false,
        val viewingSnapshot: ReconciliationSession? = null,
        val justClosedId: String? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

