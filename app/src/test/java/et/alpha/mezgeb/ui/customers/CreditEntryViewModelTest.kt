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
import et.alpha.mezgeb.domain.credit.SuggestRepaymentMatchesUseCase
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
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
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The credit form on its own: the keypad, the ranking of payments to link, and what
 * happens when a save fails (ROADMAP R43).
 *
 * The flows across the credit book's screens (a repayment offered the right
 * payments, nothing linked unless picked, correcting and removing an entry) are in
 * [CreditBookViewModelTest]. This file covers the form's own rules, which those
 * tests pass through without checking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreditEntryViewModelTest : DatabaseTest() {

    private val now = T0 + 100 * DAY
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private lateinit var customers: CustomerRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        customers = CustomerRepository(db.customerDao(), db.creditEntryDao())
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Keypad -----------------------------------------------------------------------

    @Test
    fun `the keypad builds santim, and backspace drops the last digit`() = runTest {
        db.customerDao().insert(customer("c1"))
        val viewModel = viewModel("c1", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            press(viewModel, 4, 5)
            viewModel.onEvent(CreditEntryEvent.DoubleZeroPressed)
            assertEquals(4_500L, viewModel.state.value.amountMinor)
            assertEquals("45.00", viewModel.state.value.amountText)

            viewModel.onEvent(CreditEntryEvent.BackspacePressed)
            assertEquals(450L, viewModel.state.value.amountMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the keypad stops at ten million birr`() = runTest {
        db.customerDao().insert(customer("c1"))
        val viewModel = viewModel("c1", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            // 10,000,000.00 birr is the cap, and is itself allowed.
            press(viewModel, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            assertEquals(MAX_AMOUNT_MINOR, viewModel.state.value.amountMinor)

            // One more digit, or two more, would overflow the cap: the amount stays.
            viewModel.onEvent(CreditEntryEvent.DigitPressed(5))
            viewModel.onEvent(CreditEntryEvent.DoubleZeroPressed)
            assertEquals(MAX_AMOUNT_MINOR, viewModel.state.value.amountMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `backspace on an empty amount stays at zero`() = runTest {
        db.customerDao().insert(customer("c1"))
        val viewModel = viewModel("c1", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(CreditEntryEvent.BackspacePressed)
            assertEquals(0L, viewModel.state.value.amountMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- What can be saved ------------------------------------------------------------

    @Test
    fun `a zero amount cannot be saved`() = runTest {
        db.customerDao().insert(customer("c1"))
        val viewModel = viewModel("c1", isRepayment = false)

        viewModel.state.test {
            assertFalse(awaitItemWhere { !it.isLoading }.canSave)
            viewModel.onEvent(CreditEntryEvent.Save)
            assertFalse(viewModel.state.value.isSaving)
            assertFalse(viewModel.state.value.isSaved)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(customers.observeEntries("c1").first().isEmpty())
    }

    @Test
    fun `nothing is saved for a customer that does not exist`() = runTest {
        val viewModel = viewModel("missing", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            press(viewModel, 5, 0, 0)
            assertNull(viewModel.state.value.customer)
            assertFalse(viewModel.state.value.canSave)
            viewModel.onEvent(CreditEntryEvent.Save)
            assertFalse(viewModel.state.value.isSaved)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the note is trimmed, a blank note is saved as none, and the chosen day is kept`() =
        runTest {
            db.customerDao().insert(customer("c1"))
            val first = viewModel("c1", isRepayment = false, newId = "e1")
            first.state.test {
                awaitItemWhere { !it.isLoading }
                press(first, 1, 0, 0)
                first.onEvent(CreditEntryEvent.NoteChanged("  NOTE_01 "))
                first.onEvent(CreditEntryEvent.DateChanged(now - 3 * DAY))
                first.onEvent(CreditEntryEvent.Save)
                awaitItemWhere { it.isSaved }
                cancelAndIgnoreRemainingEvents()
            }
            val second = viewModel("c1", isRepayment = false, newId = "e2")
            second.state.test {
                awaitItemWhere { !it.isLoading }
                press(second, 1, 0, 0)
                second.onEvent(CreditEntryEvent.NoteChanged("   "))
                second.onEvent(CreditEntryEvent.Save)
                awaitItemWhere { it.isSaved }
                cancelAndIgnoreRemainingEvents()
            }

            val entries = customers.observeEntries("c1").first().associateBy { it.id }
            assertEquals("NOTE_01", entries.getValue("e1").note)
            assertEquals(now - 3 * DAY, entries.getValue("e1").occurredAt)
            assertNull(entries.getValue("e2").note)
            // Defaults to now when no day was picked.
            assertEquals(now, entries.getValue("e2").occurredAt)
        }

    // --- A failed save ----------------------------------------------------------------

    @Test
    fun `a failed save says so, keeps the form, and can be retried`() = runTest {
        db.customerDao().insert(customer("c1"))
        // The id the form will use is already taken, so the insert aborts.
        db.creditEntryDao().insert(creditEntry("taken", "c1", CreditKind.CREDIT_GIVEN, 100))
        val viewModel = viewModel("c1", isRepayment = false, newId = "taken")

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            press(viewModel, 2, 5, 0)
            viewModel.onEvent(CreditEntryEvent.Save)

            val failed = awaitItemWhere { it.errorMessage != null }
            assertEquals(CreditEntryUiState.ErrorKind.SAVE_FAILED, failed.errorMessage)
            assertFalse(failed.isSaved)
            assertFalse(failed.isSaving)
            // What was typed is still there to try again with.
            assertEquals(250L, failed.amountMinor)
            assertTrue(failed.canSave)

            viewModel.onEvent(CreditEntryEvent.ErrorShown)
            assertNull(awaitItemWhere { it.errorMessage == null }.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }

        // The row that was already there is untouched.
        assertEquals(100L, customers.observeEntries("c1").first().single().amountMinor)
    }

    // --- Payments offered for a repayment ---------------------------------------------

    @Test
    fun `the payments are re-ranked on every amount change`() = runTest {
        db.customerDao().insert(customer("c1"))
        db.transactionDao().insertAll(
            listOf(
                transaction("t_small", amountMinor = 900, occurredAt = now - 2 * DAY),
                transaction("t_large", amountMinor = 45_000, occurredAt = now - 3 * DAY),
            ),
        )
        val viewModel = viewModel("c1", isRepayment = true)

        viewModel.state.test {
            // No amount yet: nothing matches, so the most recent is first.
            assertEquals(
                listOf("t_small", "t_large"),
                awaitItemWhere { !it.isLoading }.matches.map { it.transaction.id },
            )

            press(viewModel, 4, 5, 0, 0, 0)
            val large = viewModel.state.value.matches
            assertEquals("t_large", large.first().transaction.id)
            assertTrue(large.first().matchesAmount)

            // Backspace to 4,500 and retype as 9.00: the other payment moves up.
            repeat(5) { viewModel.onEvent(CreditEntryEvent.BackspacePressed) }
            press(viewModel, 9, 0, 0)
            val small = viewModel.state.value.matches
            assertEquals("t_small", small.first().transaction.id)
            assertTrue(small.first().matchesAmount)
            assertFalse(small.last().matchesAmount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `payments older than a week, and money going out, are not offered`() = runTest {
        db.customerDao().insert(customer("c1"))
        db.transactionDao().insertAll(
            listOf(
                transaction("t_recent", occurredAt = now - 6 * DAY),
                transaction("t_old", occurredAt = now - 8 * DAY),
                transaction(
                    "t_out",
                    direction = Direction.OUT,
                    occurredAt = now - DAY,
                ),
            ),
        )

        viewModel("c1", isRepayment = true).state.test {
            val ids = awaitItemWhere { !it.isLoading }.matches.map { it.transaction.id }
            assertEquals(listOf("t_recent"), ids)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `credit given is not offered payments to link`() = runTest {
        db.customerDao().insert(customer("c1"))
        db.transactionDao().insert(transaction("t1", occurredAt = now - DAY))
        val viewModel = viewModel("c1", isRepayment = false)

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            press(viewModel, 4, 5, 0, 0, 0)
            assertTrue(viewModel.state.value.matches.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `picking a second payment replaces the first, and only the picked one is saved`() =
        runTest {
            db.customerDao().insert(customer("c1"))
            db.transactionDao().insertAll(
                listOf(
                    transaction("t1", amountMinor = 45_000, occurredAt = now - DAY),
                    transaction("t2", amountMinor = 45_000, occurredAt = now - 2 * DAY),
                ),
            )
            val viewModel = viewModel("c1", isRepayment = true)

            viewModel.state.test {
                awaitItemWhere { !it.isLoading }
                press(viewModel, 4, 5, 0, 0, 0)
                viewModel.onEvent(CreditEntryEvent.TransactionPicked("t1"))
                viewModel.onEvent(CreditEntryEvent.TransactionPicked("t2"))
                assertEquals("t2", viewModel.state.value.linkedTransactionId)
                viewModel.onEvent(CreditEntryEvent.Save)
                awaitItemWhere { it.isSaved }
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals("t2", customers.observeEntries("c1").first().single().linkedTransactionId)
            assertNull(customers.repaymentForTransaction("t1"))
        }

    // --- Fixtures ---------------------------------------------------------------------

    private fun viewModel(customerId: String, isRepayment: Boolean, newId: String = "entry_1") =
        CreditEntryViewModel(
            customers = customers,
            transactions = transactions,
            matcher = SuggestRepaymentMatchesUseCase(),
            ids = IdGenerator { newId },
            clock = clock,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "customerId" to customerId,
                    "isRepayment" to isRepayment,
                    "entryId" to null,
                ),
            ),
        )

    private fun press(viewModel: CreditEntryViewModel, vararg digits: Int) {
        digits.forEach { viewModel.onEvent(CreditEntryEvent.DigitPressed(it)) }
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    private companion object {
        /** Mirrors the view model's cap: 10,000,000.00 birr. */
        const val MAX_AMOUNT_MINOR = 1_000_000_000L
    }
}
