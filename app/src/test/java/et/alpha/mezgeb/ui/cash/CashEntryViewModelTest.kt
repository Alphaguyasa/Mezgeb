package et.alpha.mezgeb.ui.cash

import app.cash.turbine.test
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class CashEntryViewModelTest : DatabaseTest() {

    private val now = Instant.parse("2026-08-01T09:30:00Z").toEpochMilli()
    private lateinit var transactions: TransactionRepository
    private lateinit var viewModel: CashEntryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        viewModel = CashEntryViewModel(
            transactions = transactions,
            rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao()),
            ids = IdGenerator { "cash_1" },
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the keypad starts at zero and cannot be saved`() {
        val state = viewModel.state.value

        assertEquals(0L, state.amountMinor)
        assertFalse(state.canSave)
        assertEquals(now, state.occurredAt)
    }

    @Test
    fun `digits accumulate as santim, till-style`() {
        // 4, 5, 0 is four birr fifty — not four hundred and fifty.
        press(4, 5, 0)

        assertEquals(450L, viewModel.state.value.amountMinor)
        assertEquals("4.50", viewModel.state.value.amountText)
    }

    @Test
    fun `the double zero key shifts two places`() {
        press(4, 5)
        viewModel.onEvent(CashEntryEvent.DoubleZeroPressed)

        assertEquals(4_500L, viewModel.state.value.amountMinor)
        assertEquals("45.00", viewModel.state.value.amountText)
    }

    @Test
    fun `backspace removes one digit at a time`() {
        press(1, 2, 3)
        viewModel.onEvent(CashEntryEvent.BackspacePressed)

        assertEquals(12L, viewModel.state.value.amountMinor)
    }

    @Test
    fun `an implausible amount is refused rather than overflowed`() {
        repeat(20) { viewModel.onEvent(CashEntryEvent.DigitPressed(9)) }

        assertTrue(viewModel.state.value.amountMinor <= 1_000_000_000L)
    }

    @Test
    fun `saving writes a cash transaction with no message behind it`() = runTest {
        db.categoryDao().insert(category("cat_sales", name = "Sales"))
        press(4, 5, 0, 0, 0)
        viewModel.onEvent(CashEntryEvent.CategoryPicked("cat_sales"))
        viewModel.onEvent(CashEntryEvent.CounterpartyChanged("  Walk-in  "))

        viewModel.onEvent(CashEntryEvent.Save)

        val saved = transactions.listTransactions(TransactionFilter()).single()
        assertEquals(45_000L, saved.amountMinor)
        assertEquals(Channel.CASH, saved.channel)
        assertEquals(Direction.IN, saved.direction)
        assertEquals("cat_sales", saved.categoryId)
        assertEquals("Walk-in", saved.counterpartyName)
        // No message to link, and nothing was changed from what a message said.
        assertNull(saved.rawMessageId)
        assertNull(saved.editedAt)
        assertTrue(viewModel.state.value.isSaved)
    }

    @Test
    fun `money out is saved as money out`() = runTest {
        press(1, 0, 0, 0)
        viewModel.onEvent(CashEntryEvent.DirectionChanged(Direction.OUT))
        viewModel.onEvent(CashEntryEvent.Save)

        assertEquals(
            Direction.OUT,
            transactions.listTransactions(TransactionFilter()).single().direction,
        )
    }

    @Test
    fun `saving nothing saves nothing`() = runTest {
        viewModel.onEvent(CashEntryEvent.Save)

        assertTrue(transactions.listTransactions(TransactionFilter()).isEmpty())
        assertFalse(viewModel.state.value.isSaved)
    }

    @Test
    fun `a failed write is reported rather than swallowed`() = runTest {
        press(5, 0, 0)
        viewModel.onEvent(CashEntryEvent.Save)

        // The id generator is fixed for the test, so saving again hits the primary key
        // and takes the same path a genuine write failure would.
        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(CashEntryEvent.Save)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, transactions.listTransactions(TransactionFilter()).size)
        assertEquals(
            CashEntryUiState.ErrorKind.SAVE_FAILED,
            viewModel.state.value.errorMessage,
        )
    }

    private fun press(vararg digits: Int) {
        digits.forEach { viewModel.onEvent(CashEntryEvent.DigitPressed(it)) }
    }
}
