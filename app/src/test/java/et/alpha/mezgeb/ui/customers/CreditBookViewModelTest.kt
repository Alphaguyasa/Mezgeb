package et.alpha.mezgeb.ui.customers

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.data.local.DAY
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.domain.credit.ComputeCustomerBalanceUseCase
import et.alpha.mezgeb.domain.credit.StatementText
import et.alpha.mezgeb.domain.credit.SuggestRepaymentMatchesUseCase
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.SessionStatus
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

    @Test
    fun `the shared statement is the statement on screen, with no phone needed`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000, phone = null)
        db.creditEntryDao().insert(
            creditEntry("e2", "c1", CreditKind.REPAYMENT, 20_000, occurredAt = T0 + DAY),
        )
        val viewModel = detailViewModel("c1")

        viewModel.state.test {
            val state = awaitItemWhere { it.statement.size == 2 }
            assertTrue(state.canShareStatement)
            assertEquals(
                listOf(
                    "Abebe",
                    "",
                    "$T0:CREDIT:+500.00 ETB:500.00 ETB",
                    "${T0 + DAY}:PAID:−200.00 ETB:300.00 ETB",
                    "",
                    "OWED 300.00 ETB",
                ).joinToString("\n"),
                viewModel.statementText(SHARE_LABELS) { it.toString() },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty statement is not offered for sharing`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 0, phone = "+251911000001")
        val viewModel = detailViewModel("c1")

        viewModel.state.test {
            assertFalse(awaitItemWhere { it.credit != null }.canShareStatement)
            assertNull(viewModel.statementText(SHARE_LABELS) { it.toString() })
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

    // --- Correcting and removing an entry (R42) ---------------------------------------

    @Test
    fun `opening an entry loads it for correction`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 500_000)

        creditEntryViewModel("c1", isRepayment = true, entryId = "c1_given").state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertTrue(state.isEditing)
            // The entry's own kind wins over the route's flag.
            assertFalse(state.isRepayment)
            assertEquals(500_000L, state.amountMinor)
            assertEquals(T0, state.occurredAt)
            assertTrue(state.matches.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a mistyped credit is corrected and the balance follows`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 500_000)
        val viewModel = creditEntryViewModel("c1", isRepayment = false, entryId = "c1_given")

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            // 5,000.00 was typed; 500.00 was meant. Two backspaces drop a zero each.
            viewModel.onEvent(CreditEntryEvent.BackspacePressed)
            viewModel.onEvent(CreditEntryEvent.BackspacePressed)
            viewModel.onEvent(CreditEntryEvent.NoteChanged("  sugar  "))
            viewModel.onEvent(CreditEntryEvent.Save)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        val entry = customers.observeEntries("c1").first().single()
        assertEquals("c1_given", entry.id)
        assertEquals(CreditKind.CREDIT_GIVEN, entry.kind)
        assertEquals(5_000L, entry.amountMinor)
        assertEquals("sugar", entry.note)
        assertEquals(5_000L, customers.observeCustomer("c1").first()?.balanceMinor)
    }

    @Test
    fun `a correction keeps the link as it is in the database, not as it was loaded`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        db.transactionDao().insert(transaction("t1", amountMinor = 45_000, occurredAt = now - DAY))
        db.creditEntryDao().insert(
            creditEntry("r1", "c1", CreditKind.REPAYMENT, 45_000, linkedTransactionId = "t1"),
        )
        val viewModel = creditEntryViewModel("c1", isRepayment = true, entryId = "r1")

        viewModel.state.test {
            assertEquals("t1", awaitItemWhere { !it.isLoading }.linkedTransactionId)
            // Unlinked on the statement while this screen was open.
            customers.linkRepaymentToTransaction("r1", null)
            viewModel.onEvent(CreditEntryEvent.DigitPressed(0))
            viewModel.onEvent(CreditEntryEvent.Save)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        val entry = customers.observeEntries("c1").first().single()
        assertEquals(450_000L, entry.amountMinor)
        assertEquals(CreditKind.REPAYMENT, entry.kind)
        assertNull(entry.linkedTransactionId)
    }

    @Test
    fun `removing an entry asks first, then the balance follows`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000, repaidMinor = 20_000)
        val viewModel = creditEntryViewModel("c1", isRepayment = true, entryId = "c1_repaid")

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            // A confirm with no question asked removes nothing.
            viewModel.onEvent(CreditEntryEvent.ConfirmDelete)
            assertFalse(viewModel.state.value.isSaved)
            assertEquals(2, customers.observeEntries("c1").first().size)

            viewModel.onEvent(CreditEntryEvent.RequestDelete)
            assertTrue(awaitItemWhere { it.isConfirmingDelete }.isConfirmingDelete)

            viewModel.onEvent(CreditEntryEvent.DismissDelete)
            awaitItemWhere { !it.isConfirmingDelete }
            assertEquals(2, customers.observeEntries("c1").first().size)

            viewModel.onEvent(CreditEntryEvent.RequestDelete)
            awaitItemWhere { it.isConfirmingDelete }
            viewModel.onEvent(CreditEntryEvent.ConfirmDelete)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("c1_given"), customers.observeEntries("c1").first().map { it.id })
        assertEquals(50_000L, customers.observeCustomer("c1").first()?.balanceMinor)
    }

    @Test
    fun `removing a linked repayment frees the payment and keeps it in the ledger`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        db.transactionDao().insert(transaction("t1", amountMinor = 45_000, occurredAt = now - DAY))
        db.creditEntryDao().insert(
            creditEntry("r1", "c1", CreditKind.REPAYMENT, 45_000, linkedTransactionId = "t1"),
        )
        val viewModel = creditEntryViewModel("c1", isRepayment = true, entryId = "r1")

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(CreditEntryEvent.RequestDelete)
            viewModel.onEvent(CreditEntryEvent.ConfirmDelete)
            awaitItemWhere { it.isSaved }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(customers.repaymentForTransaction("t1"))
        assertEquals("t1", db.transactionDao().getById("t1")?.id)
    }

    @Test
    fun `delete is not offered on a new entry`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        val viewModel = creditEntryViewModel("c1", isRepayment = false)

        viewModel.state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertFalse(state.canDelete)
            viewModel.onEvent(CreditEntryEvent.RequestDelete)
            viewModel.onEvent(CreditEntryEvent.ConfirmDelete)
            assertFalse(viewModel.state.value.isConfirmingDelete)
            assertFalse(viewModel.state.value.isSaved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an entry removed elsewhere is not re-created by saving a correction`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000)
        val viewModel = creditEntryViewModel("c1", isRepayment = false, entryId = "c1_given")

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            customers.deleteEntry("c1_given")
            viewModel.onEvent(CreditEntryEvent.Save)
            val state = awaitItemWhere { it.errorMessage != null }
            assertEquals(CreditEntryUiState.ErrorKind.SAVE_FAILED, state.errorMessage)
            assertFalse(state.isSaved)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(customers.observeEntries("c1").first().isEmpty())
    }

    @Test
    fun `another customer's entry cannot be opened through this one`() = runTest {
        seedCustomer("c1", "Abebe", givenMinor = 50_000)
        seedCustomer("c2", "Chaltu", givenMinor = 10_000)
        val viewModel = creditEntryViewModel("c2", isRepayment = false, entryId = "c1_given")

        viewModel.state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertTrue(state.entryNotFound)
            assertFalse(state.canSave)
            assertFalse(state.canDelete)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a closed period keeps its credit figures after an entry is corrected or removed`() =
        runTest {
            seedCustomer("c1", "Abebe", givenMinor = 50_000, repaidMinor = 20_000)
            val closed = ReconciliationSessionEntity(
                id = "s1",
                periodStart = T0 - DAY,
                periodEnd = T0 + DAY,
                expectedTotalMinor = null,
                capturedInMinor = 0,
                capturedOutMinor = 0,
                cashRecordedMinor = 0,
                creditGivenMinor = 50_000,
                creditRepaidMinor = 20_000,
                status = SessionStatus.CLOSED,
                closedAt = T0 + 2 * DAY,
                note = null,
            )
            db.reconciliationSessionDao().insert(closed)

            val edit = creditEntryViewModel("c1", isRepayment = false, entryId = "c1_given")
            edit.state.test {
                awaitItemWhere { !it.isLoading }
                edit.onEvent(CreditEntryEvent.BackspacePressed)
                edit.onEvent(CreditEntryEvent.Save)
                awaitItemWhere { it.isSaved }
                cancelAndIgnoreRemainingEvents()
            }
            val remove = creditEntryViewModel("c1", isRepayment = true, entryId = "c1_repaid")
            remove.state.test {
                awaitItemWhere { !it.isLoading }
                remove.onEvent(CreditEntryEvent.RequestDelete)
                remove.onEvent(CreditEntryEvent.ConfirmDelete)
                awaitItemWhere { it.isSaved }
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(5_000L, customers.observeCustomer("c1").first()?.balanceMinor)
            assertEquals(closed, db.reconciliationSessionDao().getById("s1"))
        }

    @Test
    fun `a saved entry cannot be saved a second time before the screen closes`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        val viewModel = creditEntryViewModel("c1", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { it.customer != null }
            press(viewModel, 1, 0, 0)
            viewModel.onEvent(CreditEntryEvent.Save)
            assertFalse(awaitItemWhere { it.isSaved }.canSave)
            viewModel.onEvent(CreditEntryEvent.Save)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, customers.observeEntries("c1").first().size)
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

    private fun creditEntryViewModel(
        customerId: String,
        isRepayment: Boolean,
        entryId: String? = null,
    ) =
        CreditEntryViewModel(
            customers = customers,
            transactions = transactions,
            matcher = SuggestRepaymentMatchesUseCase(),
            ids = IdGenerator { "entry_1" },
            clock = clock,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "customerId" to customerId,
                    "isRepayment" to isRepayment,
                    "entryId" to entryId,
                ),
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

    private companion object {
        /** Short labels, so the expected text shows the layout and not the wording. */
        val SHARE_LABELS = StatementText.Labels(
            title = "{name}",
            kindGiven = "CREDIT",
            kindRepayment = "PAID",
            kindAdjustment = "ADJUSTED",
            line = "{day}:{kind}:{amount}:{balance}",
            balanceOwed = "OWED {amount}",
            balanceShopOwes = "SHOP OWES {amount}",
            balanceSettled = "SETTLED",
            empty = "EMPTY",
        )
    }
}
