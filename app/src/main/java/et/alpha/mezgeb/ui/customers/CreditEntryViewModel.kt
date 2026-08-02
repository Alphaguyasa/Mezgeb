package et.alpha.mezgeb.ui.customers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.credit.RepaymentMatch
import et.alpha.mezgeb.domain.credit.SuggestRepaymentMatchesUseCase
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.navigation.MezgebDestination
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class CreditEntryUiState(
    val isLoading: Boolean = true,
    val customer: Customer? = null,
    val isRepayment: Boolean = false,
    val amountMinor: Long = 0L,
    val occurredAt: Long = 0L,
    val note: String = "",
    /** Recent unlinked payments, best match first. Repayments only. */
    val matches: List<RepaymentMatch> = emptyList(),
    val linkedTransactionId: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: ErrorKind? = null,
) {
    val canSave: Boolean get() = amountMinor > 0 && customer != null && !isSaving
    val amountText: String get() = MoneyFormatter.formatUnsigned(amountMinor)

    enum class ErrorKind { SAVE_FAILED }
}

sealed interface CreditEntryEvent {
    data class DigitPressed(val digit: Int) : CreditEntryEvent
    data object DoubleZeroPressed : CreditEntryEvent
    data object BackspacePressed : CreditEntryEvent
    data class NoteChanged(val value: String) : CreditEntryEvent
    data class DateChanged(val occurredAt: Long) : CreditEntryEvent

    /** Tapping the same payment again unlinks it. */
    data class TransactionPicked(val transactionId: String) : CreditEntryEvent

    data object Save : CreditEntryEvent
    data object ErrorShown : CreditEntryEvent
}

/**
 * Adding credit, or recording a repayment against it.
 *
 * When it is a repayment, the screen offers the payments captured in the last week
 * that nothing else claims — ranked by amount first and phone second — so the shop
 * can tie the debt to the money that actually arrived. Nothing is linked
 * automatically: a wrong link makes a debt vanish against an unrelated payment, and
 * it is discovered weeks later at reconciliation with no way to tell which record
 * lied.
 */
@HiltViewModel
class CreditEntryViewModel @Inject constructor(
    private val customers: CustomerRepository,
    private val transactions: TransactionRepository,
    private val matcher: SuggestRepaymentMatchesUseCase,
    private val ids: IdGenerator,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<MezgebDestination.CreditEntry>()

    private val _state = MutableStateFlow(
        CreditEntryUiState(isRepayment = route.isRepayment, occurredAt = clock.millis()),
    )
    val state: StateFlow<CreditEntryUiState> = _state.asStateFlow()

    private var candidates: List<Transaction> = emptyList()

    init {
        viewModelScope.launch {
            val customer = customers.getCustomer(route.customerId)
            if (route.isRepayment) {
                candidates = transactions.unlinkedIncomingSince(
                    clock.millis() - MATCH_WINDOW_DAYS * MezgebDates.DAY_MILLIS,
                )
            }
            _state.update {
                it.copy(isLoading = false, customer = customer).withMatches()
            }
        }
    }

    fun onEvent(event: CreditEntryEvent) {
        when (event) {
            is CreditEntryEvent.DigitPressed -> appendDigits(event.digit.toLong(), places = 1)
            CreditEntryEvent.DoubleZeroPressed -> appendDigits(0, places = 2)

            CreditEntryEvent.BackspacePressed ->
                _state.update { it.copy(amountMinor = it.amountMinor / 10).withMatches() }

            is CreditEntryEvent.NoteChanged -> _state.update { it.copy(note = event.value) }
            is CreditEntryEvent.DateChanged -> _state.update { it.copy(occurredAt = event.occurredAt) }

            is CreditEntryEvent.TransactionPicked -> _state.update { current ->
                current.copy(
                    linkedTransactionId = event.transactionId
                        .takeIf { it != current.linkedTransactionId },
                )
            }

            CreditEntryEvent.Save -> save()
            CreditEntryEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /** Santim accumulate from the keypad, as on the cash screen. */
    private fun appendDigits(digits: Long, places: Int) {
        _state.update { current ->
            val multiplier = if (places == 1) 10L else 100L
            val next = current.amountMinor * multiplier + digits
            if (next > MAX_AMOUNT_MINOR) current else current.copy(amountMinor = next).withMatches()
        }
    }

    /** Re-ranks on every amount change: the amount is the strongest signal there is. */
    private fun CreditEntryUiState.withMatches(): CreditEntryUiState {
        if (!isRepayment || customer == null) return this
        return copy(matches = matcher.rank(candidates, customer, amountMinor))
    }

    private fun save() {
        val current = _state.value
        val customer = current.customer ?: return
        if (!current.canSave) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = runCatching {
                customers.insertEntry(
                    CreditEntry(
                        id = ids.newId(),
                        customerId = customer.id,
                        kind = if (current.isRepayment) {
                            CreditKind.REPAYMENT
                        } else {
                            CreditKind.CREDIT_GIVEN
                        },
                        // Always positive; the kind carries the sign.
                        amountMinor = current.amountMinor,
                        occurredAt = current.occurredAt,
                        note = current.note.trim().takeIf { it.isNotEmpty() },
                        linkedTransactionId = current.linkedTransactionId,
                    ),
                )
            }

            _state.update {
                if (result.isSuccess) {
                    it.copy(isSaving = false, isSaved = true)
                } else {
                    it.copy(
                        isSaving = false,
                        errorMessage = CreditEntryUiState.ErrorKind.SAVE_FAILED,
                    )
                }
            }
        }
    }

    private companion object {
        /** Per the session brief: payments older than a week are not plausible. */
        const val MATCH_WINDOW_DAYS = 7L
        const val MAX_AMOUNT_MINOR = 1_000_000_000L
    }
}
