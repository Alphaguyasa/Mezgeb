package et.alpha.mezgeb.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.MoneyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * @param amountMinor accumulated from the keypad in santim. There is no text form:
 *   the keypad cannot produce an unparseable amount, so there is nothing to validate
 *   beyond "greater than zero".
 */
data class CashEntryUiState(
    val amountMinor: Long = 0L,
    val direction: Direction = Direction.IN,
    val categoryId: String? = null,
    val categories: List<Category> = emptyList(),
    val counterparty: String = "",
    val note: String = "",
    val occurredAt: Long = 0L,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: ErrorKind? = null,
) {
    val canSave: Boolean get() = amountMinor > 0 && !isSaving
    val amountText: String get() = MoneyFormatter.formatUnsigned(amountMinor)

    enum class ErrorKind { SAVE_FAILED }
}

sealed interface CashEntryEvent {
    data class DigitPressed(val digit: Int) : CashEntryEvent
    data object DoubleZeroPressed : CashEntryEvent
    data object BackspacePressed : CashEntryEvent
    data class DirectionChanged(val direction: Direction) : CashEntryEvent
    data class CategoryPicked(val categoryId: String?) : CashEntryEvent
    data class CounterpartyChanged(val value: String) : CashEntryEvent
    data class NoteChanged(val value: String) : CashEntryEvent
    data class DateChanged(val occurredAt: Long) : CashEntryEvent
    data object Save : CashEntryEvent
    data object ErrorShown : CashEntryEvent
}

/**
 * Hand-entered cash — the half of a shop's takings no message will ever describe.
 *
 * The resulting Transaction has `channel = CASH` and `rawMessageId = null`, which is
 * what makes it distinguishable everywhere else in the app: reconciliation counts it
 * separately, and the detail screen knows there is no original message to show.
 */
@HiltViewModel
class CashEntryViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    rules: RuleRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(CashEntryUiState(occurredAt = clock.millis()))
    val state: StateFlow<CashEntryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            rules.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
    }

    fun onEvent(event: CashEntryEvent) {
        when (event) {
            is CashEntryEvent.DigitPressed -> appendDigits(event.digit.toLong(), places = 1)
            CashEntryEvent.DoubleZeroPressed -> appendDigits(0, places = 2)

            CashEntryEvent.BackspacePressed ->
                _state.update { it.copy(amountMinor = it.amountMinor / 10) }

            is CashEntryEvent.DirectionChanged ->
                _state.update { it.copy(direction = event.direction) }

            is CashEntryEvent.CategoryPicked -> _state.update { it.copy(categoryId = event.categoryId) }

            is CashEntryEvent.CounterpartyChanged ->
                _state.update { it.copy(counterparty = event.value) }

            is CashEntryEvent.NoteChanged -> _state.update { it.copy(note = event.value) }

            is CashEntryEvent.DateChanged -> _state.update { it.copy(occurredAt = event.occurredAt) }

            CashEntryEvent.Save -> save()

            CashEntryEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /** Shifts the accumulated santim left and adds the new digits, till-style. */
    private fun appendDigits(digits: Long, places: Int) {
        _state.update { current ->
            val multiplier = if (places == 1) 10L else 100L
            val next = current.amountMinor * multiplier + digits
            // A shop's cash sale is not ten million birr, and an accidental long press
            // should not be able to overflow the field.
            if (next > MAX_AMOUNT_MINOR) current else current.copy(amountMinor = next)
        }
    }

    private fun save() {
        val current = _state.value
        if (!current.canSave) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = runCatching {
                transactions.insert(
                    Transaction(
                        id = ids.newId(),
                        // No message to link: this is the entry a message never made.
                        rawMessageId = null,
                        direction = current.direction,
                        amountMinor = current.amountMinor,
                        currency = MoneyFormatter.DEFAULT_CURRENCY,
                        channel = Channel.CASH,
                        counterpartyName = current.counterparty.trim().takeIf { it.isNotEmpty() },
                        counterpartyPhone = null,
                        providerTxnId = null,
                        balanceAfterMinor = null,
                        occurredAt = current.occurredAt,
                        categoryId = current.categoryId,
                        note = current.note.trim().takeIf { it.isNotEmpty() },
                        isReconciled = false,
                        reconciliationSessionId = null,
                        isDeleted = false,
                        // Typed by hand from the start, so there is nothing to mark as
                        // having been changed from what a message said.
                        editedAt = null,
                    ),
                )
            }

            _state.update {
                if (result.isSuccess) {
                    it.copy(isSaving = false, isSaved = true)
                } else {
                    it.copy(isSaving = false, errorMessage = CashEntryUiState.ErrorKind.SAVE_FAILED)
                }
            }
        }
    }

    private companion object {
        /** Ten million birr in santim. */
        const val MAX_AMOUNT_MINOR = 1_000_000_000L
    }
}
