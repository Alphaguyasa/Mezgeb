package et.alpha.mezgeb.ui.customers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.credit.ComputeCustomerBalanceUseCase
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.credit.ReminderMessage
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.navigation.MezgebDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class CustomerDetailUiState(
    val isLoading: Boolean = true,
    val credit: CustomerCredit? = null,
    val statement: List<CreditStatementLine> = emptyList(),
    val isConfirmingArchive: Boolean = false,
    val isArchived: Boolean = false,
    val editor: CustomerEditor? = null,
    val notFound: Boolean = false,
) {
    val canRemind: Boolean
        get() = credit?.owesMoney == true && !credit.customer.phone.isNullOrBlank()

    val isEmpty: Boolean get() = statement.isEmpty()
}

/** Name and phone, while they are being edited. */
data class CustomerEditor(val name: String, val phone: String)

sealed interface CustomerDetailEvent {
    data object StartEdit : CustomerDetailEvent
    data class EditorChanged(val editor: CustomerEditor) : CustomerDetailEvent
    data object CancelEdit : CustomerDetailEvent
    data object SaveEdit : CustomerDetailEvent

    data object RequestArchive : CustomerDetailEvent
    data object DismissArchive : CustomerDetailEvent
    data object ConfirmArchive : CustomerDetailEvent

    /** Break a repayment's link to a captured payment. */
    data class UnlinkEntry(val entryId: String) : CustomerDetailEvent
}

/**
 * One customer's running statement.
 *
 * The statement is the credit book's whole point: a shop owner and a customer can
 * disagree about a balance, and the only useful answer is a list of what was given
 * and what was paid, in order, with the balance after each line.
 */
@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    private val customers: CustomerRepository,
    private val balances: ComputeCustomerBalanceUseCase,
    settings: SettingsRepository,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val customerId =
        savedStateHandle.toRoute<MezgebDestination.CustomerDetail>().customerId

    private val local = MutableStateFlow(LocalState())

    /** Null until read; the UI supplies the localised default when it is. */
    private var storedTemplate: String? = null

    val state: StateFlow<CustomerDetailUiState> = combine(
        customers.observeCustomer(customerId),
        customers.observeStatement(customerId),
        settings.observeOverdueThresholdDays(),
        settings.observeReminderTemplate(),
        local,
    ) { balance, statement, overdueDays, template, localState ->
        storedTemplate = template
        CustomerDetailUiState(
            isLoading = false,
            credit = balance?.let { balances.summarise(it, clock.millis(), overdueDays) },
            statement = statement,
            isConfirmingArchive = localState.isConfirmingArchive,
            isArchived = balance?.customer?.isArchived == true,
            editor = localState.editor,
            notFound = balance == null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = CustomerDetailUiState(),
    )

    fun onEvent(event: CustomerDetailEvent) {
        when (event) {
            CustomerDetailEvent.StartEdit -> {
                val customer = state.value.credit?.customer ?: return
                local.update {
                    it.copy(editor = CustomerEditor(customer.name, customer.phone.orEmpty()))
                }
            }

            is CustomerDetailEvent.EditorChanged -> local.update { it.copy(editor = event.editor) }
            CustomerDetailEvent.CancelEdit -> local.update { it.copy(editor = null) }
            CustomerDetailEvent.SaveEdit -> saveEdit()

            CustomerDetailEvent.RequestArchive -> local.update { it.copy(isConfirmingArchive = true) }
            CustomerDetailEvent.DismissArchive -> local.update { it.copy(isConfirmingArchive = false) }

            CustomerDetailEvent.ConfirmArchive -> viewModelScope.launch {
                // Archived, not deleted: deleting cascades the credit history away, and
                // a settled customer is exactly who you need records for later.
                customers.setArchived(customerId, true)
                local.update { it.copy(isConfirmingArchive = false) }
            }

            is CustomerDetailEvent.UnlinkEntry -> viewModelScope.launch {
                customers.linkRepaymentToTransaction(event.entryId, null)
            }
        }
    }

    /**
     * The reminder text, or null when there is nothing to remind about.
     *
     * @param defaultTemplate the built-in template for the current language. Passed in
     *   rather than read here, because the default is a translated resource and this
     *   class has no business holding a Context to reach one.
     */
    fun reminderMessage(defaultTemplate: String): String? {
        val credit = state.value.credit ?: return null
        if (!credit.owesMoney) return null
        return ReminderMessage.render(
            template = storedTemplate?.takeIf { it.isNotBlank() } ?: defaultTemplate,
            customerName = credit.customer.name,
            balanceMinor = credit.balanceMinor,
        )
    }

    fun customerPhone(): String? = state.value.credit?.customer?.phone

    private fun saveEdit() {
        val editor = local.value.editor ?: return
        val existing = state.value.credit?.customer ?: return
        val name = editor.name.trim()
        if (name.isEmpty()) return

        viewModelScope.launch {
            customers.updateCustomer(
                existing.copy(
                    name = name,
                    phone = editor.phone.trim().takeIf { it.isNotEmpty() },
                ),
            )
            local.update { it.copy(editor = null) }
        }
    }

    private data class LocalState(
        val isConfirmingArchive: Boolean = false,
        val editor: CustomerEditor? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
