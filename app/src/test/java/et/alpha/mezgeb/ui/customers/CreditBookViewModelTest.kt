package et.alpha.mezgeb.ui.customers

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.data.local.DAY
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.domain.credit.ComputeCustomerBalanceUseCase
import et.alpha.mezgeb.domain.credit.SuggestRepaymentMatchesUseCase
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The credit book's three screens, over a real database.
 *
 * The assertions that matter are the ones about money leaving the shop's view:
 * archiving must not delete a history, and a repayment must only be linked to a
 * payment a person actually chose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreditBookViewModelTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = T0 + 100 * DAY
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private lateinit var settingsHarness: SettingsHarness
    private lateinit var customers: CustomerRepository
    private lateinit var transactions: TransactionRepository
    private val balances by lazy { ComputeCustomerBalanceUseCase(zone) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsHarness = SettingsHarness(tempFolder.root)
        customers = CustomerRepository(db.customerDao(), db.creditEntryDao())
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    // --- Customers list ---------------------------------------------------------------

    @Test
    fun `an empty credit book is empty`() = runTest {
        customersViewModel().state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertTrue(state.isEmpty)
            assertEquals(0L, state.totalOutstandingMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the header states what is owed, and by how many people`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000)
        seedCustomer("c2", "Chaltu", givenMinor = 12_000)
        // A customer the shop owes money to must not reduce the debt being carried.
        seedCustomer("c3", "Kebede", givenMinor = 1_000, repaidMinor = 6_000)

        customersViewModel().state.test {
            val state = awaitItemWhere { it.customers.isNotEmpty() }
            assertEquals(62_000L, state.totalOutstandingMinor)
            assertEquals(2, state.customersInDebt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `customers are listed by what they owe, worst first`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 10_000)
        seedCustomer("c2", "Chaltu", givenMinor = 90_000)

        customersViewModel().state.test {
            val state = awaitItemWhere { it.customers.size == 2 }
            assertEquals(listOf("Chaltu", "Abebe"), state.customers.map { it.customer.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an old debt is flagged overdue against the shop's own threshold`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 10_000, givenAt = now - 45 * DAY)
        settingsHarness.repository.setOverdueThresholdDays(30)

        customersViewModel().state.test {
            val credit = awaitItemWhere { it.customers.isNotEmpty() }.customers.single()
            assertEquals(45, credit.daysOutstanding)
            assertTrue(credit.isOverdue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding a customer puts them on the list`() = runTest {
        val viewModel = customersViewModel()

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(CustomersEvent.AddCustomer("Abebe Bekele", " +251911000001 "))

            val state = awaitItemWhere { it.customers.isNotEmpty() }
            assertEquals("Abebe Bekele", state.customers.single().customer.name)
            assertEquals("+251911000001", state.customers.single().customer.phone)
            assertFalse(state.isAddingCustomer)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Customer detail --------------------------------------------------------------

    @Test
    fun `the statement carries a running balance`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000)
        db.creditEntryDao().insert(
            creditEntry("e2", "c1", CreditKind.REPAYMENT, 20_000, occurredAt = T0 + DAY),
        )

        detailViewModel("c1").state.test {
            val state = awaitItemWhere { it.statement.size == 2 }
            assertEquals(listOf(50_000L, 30_000L), state.statement.map { it.runningBalanceMinor })
            assertEquals(30_000L, state.credit?.balanceMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `archiving takes a customer off the list and keeps their history`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000)
        val viewModel = detailViewModel("c1")

        viewModel.state.test {
            awaitItemWhere { it.credit != null }
            viewModel.onEvent(CustomerDetailEvent.ConfirmArchive)
            assertTrue(awaitItemWhere { it.isArchived }.isArchived)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(customers.observeCustomersWithBalance().first().isEmpty())
        // Deleting would have cascaded this away.
        assertEquals(1, customers.observeEntries("c1").first().size)
    }

    @Test
    fun `a reminder is offered only when there is something to remind about`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000, phone = "+251911000001")
        seedCustomer("c2", "Chaltu", givenMinor = 0, phone = "+251911000002")

        detailViewModel("c1").state.test {
            assertTrue(awaitItemWhere { it.credit != null }.canRemind)
            cancelAndIgnoreRemainingEvents()
        }
        detailViewModel("c2").state.test {
            assertFalse(awaitItemWhere { it.credit != null }.canRemind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a customer with no phone cannot be reminded`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000, phone = null)

        detailViewModel("c1").state.test {
            assertFalse(awaitItemWhere { it.credit != null }.canRemind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the reminder uses the shop's own wording when it has set one`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 45_000, phone = "+251911000001")
        settingsHarness.repository.setReminderTemplate("Ato {name}, {amount} qq.")
        val viewModel = detailViewModel("c1")

        viewModel.state.test {
            awaitItemWhere { it.credit != null }
            assertEquals(
                "Ato Abebe, 450.00 ETB qq.",
                viewModel.reminderMessage("unused default"),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Credit entry -----------------------------------------------------------------

    @Test
    fun `giving credit adds to what the customer owes`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        val viewModel = creditEntryViewModel("c1", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { it.customer != null }
            press(viewModel, 4, 5, 0, 0, 0)
            viewModel.onEvent(CreditEntryEvent.Save)
            assertTrue(awaitItemWhere { it.isSaved }.isSaved)
            cancelAndIgnoreRemainingEvents()
        }

        val entry = customers.observeEntries("c1").first().single()
        assertEquals(CreditKind.CREDIT_GIVEN, entry.kind)
        assertEquals(45_000L, entry.amountMinor)
        assertNull(entry.linkedTransactionId)
    }

    @Test
    fun `a repayment is offered the payments that arrived, best match first`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe", phone = "+251911000001"))
        db.transactionDao().insertAll(
            listOf(
                transaction("t_other", amountMinor = 900, counterpartyPhone = null, occurredAt = now - DAY),
                transaction("t_match", amountMinor = 45_000, counterpartyPhone = null, occurredAt = now - DAY),
            ),
        )
        val viewModel = creditEntryViewModel("c1", isRepayment = true)

        viewModel.state.test {
            awaitItemWhere { it.customer != null }
            press(viewModel, 4, 5, 0, 0, 0)

            val state = awaitItemWhere { it.matches.any { match -> match.matchesAmount } }
            assertEquals("t_match", state.matches.first().transaction.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a payment already claimed by another repayment is not offered again`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        db.transactionDao().insert(transaction("t1", amountMinor = 45_000, occurredAt = now - DAY))
        db.creditEntryDao().insert(
            creditEntry(
                id = "existing",
                customerId = "c1",
                kind = CreditKind.REPAYMENT,
                amountMinor = 45_000,
                occurredAt = now - DAY,
                linkedTransactionId = "t1",
            ),
        )

        creditEntryViewModel("c1", isRepayment = true).state.test {
            val state = awaitItemWhere { !it.isLoading }
            // One payment cannot settle two debts.
            assertTrue(state.matches.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `nothing is linked unless a person picks it`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        db.transactionDao().insert(transaction("t1", amountMinor = 45_000, occurredAt = now - DAY))
        val viewModel = creditEntryViewModel("c1", isRepayment = true)

        viewModel.state.test {
            awaitItemWhere { it.customer != null }
            press(viewModel, 4, 5, 0, 0, 0)
            awaitItemWhere { it.matches.isNotEmpty() }
            viewModel.onEvent(CreditEntryEvent.Save)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        // A perfect match was on screen and was not chosen, so it is not linked.
        assertNull(customers.observeEntries("c1").first().single().linkedTransactionId)
    }

    @Test
    fun `picking a payment links it, and picking it again unlinks it`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        db.transactionDao().insert(transaction("t1", amountMinor = 45_000, occurredAt = now - DAY))
        val viewModel = creditEntryViewModel("c1", isRepayment = true)

        viewModel.state.test {
            awaitItemWhere { it.customer != null }
            press(viewModel, 4, 5, 0, 0, 0)
            awaitItemWhere { it.matches.isNotEmpty() }

            viewModel.onEvent(CreditEntryEvent.TransactionPicked("t1"))
            assertEquals("t1", awaitItemWhere { it.linkedTransactionId != null }.linkedTransactionId)

            viewModel.onEvent(CreditEntryEvent.TransactionPicked("t1"))
            assertNull(awaitItemWhere { it.linkedTransactionId == null }.linkedTransactionId)

            viewModel.onEvent(CreditEntryEvent.TransactionPicked("t1"))
            awaitItemWhere { it.linkedTransactionId != null }
            viewModel.onEvent(CreditEntryEvent.Save)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("t1", customers.observeEntries("c1").first().single().linkedTransactionId)
    }

    @Test
    fun `a repayment larger than the balance is accepted`() = runTest {
        // The shop ends up holding the customer's money. That happens, so it saves.
        seedCustomer("c1", "Abebe", givenMinor = 10_000)
        val viewModel = creditEntryViewModel("c1", isRepayment = true)

        viewModel.state.test {
            awaitItemWhere { it.customer != null }
            press(viewModel, 1, 5, 0, 0, 0)
            viewModel.onEvent(CreditEntryEvent.Save)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(-5_000L, customers.observeCustomer("c1").first()?.balanceMinor)
    }

    // --- Fixtures ---------------------------------------------------------------------

    private fun customersViewModel() = CustomersViewModel(
        customers = customers,
        balances = balances,
        settings = settingsHarness.repository,
        ids = IdGenerator { "generated" },
        clock = clock,
    )

    private fun detailViewModel(customerId: String) = CustomerDetailViewModel(
        customers = customers,
        balances = balances,
        settings = settingsHarness.repository,
        clock = clock,
        savedStateHandle = SavedStateHandle(mapOf("customerId" to customerId)),
    )

    private fun creditEntryViewModel(customerId: String, isRepayment: Boolean) =
        CreditEntryViewModel(
            customers = customers,
            transactions = transactions,
            matcher = SuggestRepaymentMatchesUseCase(),
            ids = IdGenerator { "entry_1" },
            clock = clock,
            savedStateHandle = SavedStateHandle(
                mapOf("customerId" to customerId, "isRepayment" to isRepayment),
            ),
        )

    private fun press(viewModel: CreditEntryViewModel, vararg digits: Int) {
        digits.forEach { viewModel.onEvent(CreditEntryEvent.DigitPressed(it)) }
    }

    private suspend fun seedCustomer(
        id: String,
        name: String,
        givenMinor: Long,
        repaidMinor: Long = 0,
        givenAt: Long = T0,
        phone: String? = null,
    ) {
        db.customerDao().insert(customer(id, name = name, phone = phone))
        if (givenMinor > 0) {
            db.creditEntryDao().insert(
                creditEntry("${id}_given", id, CreditKind.CREDIT_GIVEN, givenMinor, occurredAt = givenAt),
            )
        }
        if (repaidMinor > 0) {
            db.creditEntryDao().insert(
                creditEntry("${id}_repaid", id, CreditKind.REPAYMENT, repaidMinor, occurredAt = givenAt),
            )
        }
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
