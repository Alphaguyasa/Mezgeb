package et.alpha.mezgeb.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.credit.ComputeCustomerBalanceUseCase
import et.alpha.mezgeb.domain.credit.CustomerCredit
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

data class CustomersUiState(
    val isLoading: Boolean = true,
    val customers: List<CustomerCredit> = emptyList(),
    val totalOutstandingMinor: Long = 0L,
    val customersInDebt: Int = 0,
    val searchQuery: String = "",
    val isAddingCustomer: Boolean = false,
    val errorMessage: ErrorKind? = null,
) {
    val isSearching: Boolean get() = searchQuery.isNotBlank()
    val isEmpty: Boolean get() = customers.isEmpty()

    enum class ErrorKind { SAVE_FAILED }
}

sealed interface CustomersEvent {
    data class SearchChanged(val query: String) : CustomersEvent
    data object AddCustomerRequested : CustomersEvent
    data object AddCustomerDismissed : CustomersEvent
    data class AddCustomer(val name: String, val phone: String) : CustomersEvent
    data object ErrorShown : CustomersEvent
}

/**
 * The credit book's front page: who owes what, worst first.
 *
 * Sorted by balance rather than by name because the question this screen answers is
 * "who should I chase", and an alphabetical list makes that a scan instead of a look.
 */
@HiltViewModel
class CustomersViewModel @Inject constructor(
    private val customers: CustomerRepository,
    private val balances: ComputeCustomerBalanceUseCase,
    settings: SettingsRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val local = MutableStateFlow(LocalState())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val state: StateFlow<CustomersUiState> = combine(
        query.debounce(SEARCH_DEBOUNCE_MILLIS).distinctUntilChanged()
            .flatMapLatest { search ->
                if (search.isBlank()) {
                    customers.observeCustomersWithBalance()
                } else {
                    customers.searchCustomers(search)
                }
            },
        customers.observeTotalOutstandingMinor(),
        customers.observeCustomersInDebtCount(),
        settings.observeOverdueThresholdDays(),
        local,
    ) { balanceRows, outstanding, inDebt, overdueDays, localState ->
        val now = clock.millis()
        CustomersUiState(
            isLoading = false,
            customers = balanceRows.map { balances.summarise(it, now, overdueDays) },
            totalOutstandingMinor = outstanding,
            customersInDebt = inDebt,
            searchQuery = localState.query,
            isAddingCustomer = localState.isAddingCustomer,
            errorMessage = localState.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = CustomersUiState(),
    )

    fun onEvent(event: CustomersEvent) {
        when (event) {
            is CustomersEvent.SearchChanged -> {
                query.value = event.query
                local.update { it.copy(query = event.query) }
            }

            CustomersEvent.AddCustomerRequested -> local.update { it.copy(isAddingCustomer = true) }
            CustomersEvent.AddCustomerDismissed -> local.update { it.copy(isAddingCustomer = false) }
            is CustomersEvent.AddCustomer -> addCustomer(event)
            CustomersEvent.ErrorShown -> local.update { it.copy(errorMessage = null) }
        }
    }

    private fun addCustomer(event: CustomersEvent.AddCustomer) {
        val name = event.name.trim()
        if (name.isEmpty()) return

        viewModelScope.launch {
            val result = runCatching {
                customers.insertCustomer(
                    Customer(
                        id = ids.newId(),
                        name = name,
                        phone = event.phone.trim().takeIf { it.isNotEmpty() },
                        note = null,
                        createdAt = clock.millis(),
                    ),
                )
            }
            local.update {
                if (result.isSuccess) {
                    it.copy(isAddingCustomer = false)
                } else {
                    it.copy(errorMessage = CustomersUiState.ErrorKind.SAVE_FAILED)
                }
            }
        }
    }

    private data class LocalState(
        val query: String = "",
        val isAddingCustomer: Boolean = false,
        val errorMessage: CustomersUiState.ErrorKind? = null,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
