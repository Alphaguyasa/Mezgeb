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
    /** Correcting an entry already on the statement, rather than adding one. */
    val isEditing: Boolean = false,
    /** The entry being corrected is gone (removed elsewhere); there is nothing to show. */
    val entryNotFound: Boolean = false,
    val isConfirmingDelete: Boolean = false,
) {
    /**
     * [isSaved] is part of the guard: between a save finishing and the screen closing,
     * one more tap would otherwise record the same credit a second time.
     */
    val canSave: Boolean
        get() = amountMinor > 0 && customer != null && !isSaving && !isSaved && !entryNotFound

    val canDelete: Boolean
        get() = isEditing && !isLoading && !isSaving && !isSaved && !entryNotFound
    val amountText: String get() = MoneyFormatter.formatUnsigned(amountMinor)

    enum class ErrorKind { SAVE_FAILED, DELETE_FAILED }
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

    /** Deleting is two steps: ask, then confirm. Editing only. */
    data object RequestDelete : CreditEntryEvent
    data object DismissDelete : CreditEntryEvent
    data object ConfirmDelete : CreditEntryEvent
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
 *
 * Opened with an entry id, the same form corrects that entry instead (a mistyped
 * 5,000 for 500 would otherwise sit in the balance for good) or removes it. A
 * correction changes the amount, the day and the note only. The kind stays, because
 * turning credit into a repayment is a different event, not a typo. The link stays
 * too, and is not offered for change here: the statement already has an unlink
 * action, and offering a fresh list of payments would replace a link without the
 * person seeing which one it replaced.
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
            val entryId = route.entryId
            if (entryId != null) {
                loadForEdit(entryId, customer)
                return@launch
            }
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

            CreditEntryEvent.RequestDelete -> _state.update {
                if (it.canDelete) it.copy(isConfirmingDelete = true) else it
            }
            CreditEntryEvent.DismissDelete -> _state.update { it.copy(isConfirmingDelete = false) }
            CreditEntryEvent.ConfirmDelete -> delete()
        }
    }

    private suspend fun loadForEdit(entryId: String, customer: Customer?) {
        // An entry that belongs to someone else is treated as missing: the route
        // names both, and they have to agree before anything is changed.
        val entry = customers.getEntry(entryId)?.takeIf { it.customerId == route.customerId }
        _state.update {
            if (entry == null) {
                it.copy(
                    isLoading = false,
                    customer = customer,
                    isEditing = true,
                    entryNotFound = true,
                )
            } else {
                it.copy(
                    isLoading = false,
                    customer = customer,
                    isEditing = true,
                    isRepayment = entry.kind == CreditKind.REPAYMENT,
                    amountMinor = entry.amountMinor,
                    occurredAt = entry.occurredAt,
                    note = entry.note.orEmpty(),
                    linkedTransactionId = entry.linkedTransactionId,
                )
            }
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
        if (!isRepayment || customer == null || isEditing) return this
        return copy(matches = matcher.rank(candidates, customer, amountMinor))
    }

    private fun save() {
        val current = _state.value
        val customer = current.customer ?: return
        if (!current.canSave) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val note = current.note.trim().takeIf { it.isNotEmpty() }
            val entryId = route.entryId
            val result = runCatching {
                if (entryId != null) {
                    val changed = customers.correctEntry(
                        id = entryId,
                        amountMinor = current.amountMinor,
                        occurredAt = current.occurredAt,
                        note = note,
                    )
                    // Removed elsewhere while this screen was open: saving it would
                    // have to re-create it, which is not what a correction means.
                    check(changed) { "entry no longer exists" }
                    return@runCatching
                }
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
                        note = note,
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

    private fun delete() {
        val entryId = route.entryId ?: return
        // Only from the confirm dialog: nothing is removed on a single tap.
        val current = _state.value
        if (!current.isConfirmingDelete || !current.canDelete) return

        _state.update { it.copy(isSaving = true, isConfirmingDelete = false) }
        viewModelScope.launch {
            // An entry already gone counts as removed: the outcome the person asked
            // for is true either way.
            val result = runCatching { customers.deleteEntry(entryId) }
            _state.update {
                if (result.isSuccess) {
                    it.copy(isSaving = false, isSaved = true)
                } else {
                    it.copy(
                        isSaving = false,
                        errorMessage = CreditEntryUiState.ErrorKind.DELETE_FAILED,
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
