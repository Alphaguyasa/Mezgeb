package et.alpha.mezgeb.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.CategoryRule
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.navigation.MezgebDestination
import et.alpha.mezgeb.parser.normalize.AmountNormalizer
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * The editable copy of a transaction's parsed fields.
 *
 * Amount is held as text, not as a Long, because a half-typed "45" is a legitimate
 * intermediate state and a Long cannot represent one. It becomes santim only on save,
 * through the same [AmountNormalizer] the parser uses — so a hand-typed amount and a
 * captured one are interpreted by exactly one piece of code.
 */
data class TransactionEditor(
    val amountText: String,
    val direction: Direction,
    val channel: Channel,
    val counterpartyName: String,
    val counterpartyPhone: String,
    val occurredAt: Long,
    val note: String,
    val amountIsInvalid: Boolean = false,
)

data class TransactionDetailUiState(
    val isLoading: Boolean = true,
    val transaction: Transaction? = null,
    /** The message this was parsed from. Null for a manual cash entry. */
    val rawMessage: RawMessage? = null,
    val categories: List<Category> = emptyList(),
    val categoryName: String? = null,
    val isBodyExpanded: Boolean = false,
    /** Non-null while editing. The view is read-only otherwise. */
    val editor: TransactionEditor? = null,
    val isConfirmingDelete: Boolean = false,
    /** Set once the row is soft-deleted, so the screen can close itself. */
    val isDeleted: Boolean = false,
    val notFound: Boolean = false,
) {
    val isEditing: Boolean get() = editor != null

    /** True when the counterparty gives a category rule something to match on. */
    val canCreateCategoryRule: Boolean
        get() = !transaction?.counterpartyName.isNullOrBlank() ||
            !transaction?.counterpartyPhone.isNullOrBlank()
}

sealed interface TransactionDetailEvent {
    data object ToggleBody : TransactionDetailEvent
    data object StartEdit : TransactionDetailEvent
    data object CancelEdit : TransactionDetailEvent
    data class EditorChanged(val editor: TransactionEditor) : TransactionDetailEvent
    data object SaveEdit : TransactionDetailEvent

    data class CategoryPicked(
        val categoryId: String?,
        /** Also writes a CategoryRule so the next message like this is automatic. */
        val alwaysApply: Boolean,
    ) : TransactionDetailEvent

    data object ToggleReconciled : TransactionDetailEvent
    data object RequestDelete : TransactionDetailEvent
    data object DismissDelete : TransactionDetailEvent
    data object ConfirmDelete : TransactionDetailEvent
}

/**
 * One transaction, with the message it came from.
 *
 * The verbatim body is the trust feature of this app. A shop owner who cannot check
 * what the app read has to take the number on faith, and this app's whole claim is
 * that she should not have to. It is never summarised, never re-flowed, never
 * "cleaned up" — it is shown exactly as it was received.
 */
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val rules: RuleRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transactionId =
        savedStateHandle.toRoute<MezgebDestination.TransactionDetail>().transactionId

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<TransactionDetailUiState> = combine(
        transactions.observeTransaction(transactionId),
        rules.observeCategories(),
        local,
    ) { transaction, categories, localState ->
        TransactionDetailUiState(
            isLoading = false,
            transaction = transaction,
            rawMessage = localState.rawMessage,
            categories = categories,
            categoryName = categories.firstOrNull { it.id == transaction?.categoryId }?.name,
            isBodyExpanded = localState.isBodyExpanded,
            editor = localState.editor,
            isConfirmingDelete = localState.isConfirmingDelete,
            isDeleted = transaction?.isDeleted == true,
            notFound = transaction == null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = TransactionDetailUiState(),
    )

    init {
        viewModelScope.launch {
            val transaction = transactions.getTransaction(transactionId) ?: return@launch
            val raw = transaction.rawMessageId?.let { transactions.getRawMessage(it) }
            local.update { it.copy(rawMessage = raw) }
        }
    }

    fun onEvent(event: TransactionDetailEvent) {
        when (event) {
            TransactionDetailEvent.ToggleBody ->
                local.update { it.copy(isBodyExpanded = !it.isBodyExpanded) }

            TransactionDetailEvent.StartEdit -> {
                val transaction = state.value.transaction ?: return
                local.update { it.copy(editor = transaction.toEditor()) }
            }

            TransactionDetailEvent.CancelEdit -> local.update { it.copy(editor = null) }

            is TransactionDetailEvent.EditorChanged ->
                local.update { it.copy(editor = event.editor.copy(amountIsInvalid = false)) }

            TransactionDetailEvent.SaveEdit -> save()

            is TransactionDetailEvent.CategoryPicked -> applyCategory(event)

            TransactionDetailEvent.ToggleReconciled -> viewModelScope.launch {
                val transaction = state.value.transaction ?: return@launch
                transactions.setReconciled(
                    id = transaction.id,
                    isReconciled = !transaction.isReconciled,
                    // Reconciling by hand outside a session leaves no session to point
                    // at. Session 8 sets this when closing a period.
                    sessionId = transaction.reconciliationSessionId,
                )
            }

            TransactionDetailEvent.RequestDelete ->
                local.update { it.copy(isConfirmingDelete = true) }

            TransactionDetailEvent.DismissDelete ->
                local.update { it.copy(isConfirmingDelete = false) }

            TransactionDetailEvent.ConfirmDelete -> viewModelScope.launch {
                // Soft delete. A shop's ledger never loses rows outright.
                transactions.softDelete(transactionId, clock.millis())
                local.update { it.copy(isConfirmingDelete = false) }
            }
        }
    }

    private fun save() {
        val editor = local.value.editor ?: return
        val original = state.value.transaction ?: return

        val amountMinor = AmountNormalizer.toMinor(editor.amountText)
        if (amountMinor == null || amountMinor <= 0) {
            local.update { it.copy(editor = editor.copy(amountIsInvalid = true)) }
            return
        }

        val updated = original.copy(
            amountMinor = amountMinor,
            direction = editor.direction,
            channel = editor.channel,
            counterpartyName = editor.counterpartyName.trim().takeIf { it.isNotEmpty() },
            counterpartyPhone = editor.counterpartyPhone.trim().takeIf { it.isNotEmpty() },
            occurredAt = editor.occurredAt,
            note = editor.note.trim().takeIf { it.isNotEmpty() },
        )

        // `editedAt` means "a human changed what the message said", and the ledger
        // shows a marker based on it. Adding a note changes nothing the parser
        // extracted, so it does not earn the marker.
        val parsedFieldsChanged = updated.amountMinor != original.amountMinor ||
            updated.direction != original.direction ||
            updated.channel != original.channel ||
            updated.counterpartyName != original.counterpartyName ||
            updated.counterpartyPhone != original.counterpartyPhone ||
            updated.occurredAt != original.occurredAt

        viewModelScope.launch {
            transactions.update(
                updated.copy(
                    editedAt = if (parsedFieldsChanged) clock.millis() else original.editedAt,
                ),
            )
            local.update { it.copy(editor = null) }
        }
    }

    private fun applyCategory(event: TransactionDetailEvent.CategoryPicked) {
        val transaction = state.value.transaction ?: return
        viewModelScope.launch {
            transactions.setCategory(transaction.id, event.categoryId, clock.millis())

            if (!event.alwaysApply || event.categoryId == null) return@launch
            categoryRuleFor(transaction, event.categoryId)?.let { rules.insertCategoryRule(it) }
        }
    }

    /**
     * "Always categorise like this" as a rule the user could later read and delete,
     * rather than as an invisible learned preference. A name is matched loosely
     * because providers render it inconsistently; a phone number is matched exactly
     * because it either is the same number or it is not.
     */
    private fun categoryRuleFor(transaction: Transaction, categoryId: String): CategoryRule? {
        val name = transaction.counterpartyName?.trim()?.takeIf { it.isNotEmpty() }
        val phone = transaction.counterpartyPhone?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            name != null -> CategoryRule(
                id = ids.newId(),
                matchField = MatchField.COUNTERPARTY_NAME,
                matchType = MatchType.CONTAINS,
                pattern = name,
                categoryId = categoryId,
                priority = USER_RULE_PRIORITY,
            )

            phone != null -> CategoryRule(
                id = ids.newId(),
                matchField = MatchField.COUNTERPARTY_PHONE,
                matchType = MatchType.EQUALS,
                pattern = phone,
                categoryId = categoryId,
                priority = USER_RULE_PRIORITY,
            )

            else -> null
        }
    }

    private fun Transaction.toEditor() = TransactionEditor(
        amountText = editableAmount(amountMinor),
        direction = direction,
        channel = channel,
        counterpartyName = counterpartyName.orEmpty(),
        counterpartyPhone = counterpartyPhone.orEmpty(),
        occurredAt = occurredAt,
        note = note.orEmpty(),
    )

    private data class LocalState(
        val rawMessage: RawMessage? = null,
        val isBodyExpanded: Boolean = false,
        val editor: TransactionEditor? = null,
        val isConfirmingDelete: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Above the bundled rules, so a shop's own decision wins. */
        const val USER_RULE_PRIORITY = 100
    }
}

/**
 * Santim to an editable string: "45000" becomes "450.00".
 *
 * Not [et.alpha.mezgeb.util.MoneyFormatter], deliberately — that groups thousands
 * with commas for reading, and feeding a comma back into an input field is how you
 * get an amount that will not re-parse.
 */
internal fun editableAmount(amountMinor: Long): String {
    val major = amountMinor / 100
    val santim = (amountMinor % 100).toInt()
    return "$major." + santim.toString().padStart(2, '0')
}
