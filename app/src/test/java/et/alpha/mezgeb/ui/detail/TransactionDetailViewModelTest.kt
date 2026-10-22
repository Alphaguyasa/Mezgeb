package et.alpha.mezgeb.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionDetailViewModelTest : DatabaseTest() {

    private val now = Instant.parse("2026-08-02T10:00:00Z").toEpochMilli()
    private lateinit var transactions: TransactionRepository
    private lateinit var rules: RuleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an id that matches nothing is reported, not left loading`() = runTest {
        viewModel("missing").state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertTrue(state.notFound)
            assertNull(state.transaction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the message the transaction was parsed from is loaded with it`() = runTest {
        seedParsedTransaction()

        viewModel("t1").state.test {
            val state = awaitItemWhere { it.rawMessage != null }
            // The trust feature: the body, verbatim, exactly as it arrived.
            assertEquals("you have received ETB 450.00", state.rawMessage?.body)
            assertEquals("Sales", state.categoryName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a manual cash entry has no message and says so by omission`() = runTest {
        db.transactionDao().insert(transaction("t1", rawMessageId = null))

        viewModel("t1").state.test {
            val state = awaitItemWhere { it.transaction != null }
            assertNull(state.rawMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `editing a parsed field marks the row as edited`() = runTest {
        seedParsedTransaction()
        val viewModel = viewModel("t1")

        viewModel.state.test {
            val loaded = awaitItemWhere { it.transaction != null }
            viewModel.onEvent(TransactionDetailEvent.StartEdit)
            val editor = awaitItemWhere { it.editor != null }.editor!!
            assertEquals("450.00", editor.amountText)

            viewModel.onEvent(
                TransactionDetailEvent.EditorChanged(editor.copy(amountText = "460.50")),
            )
            viewModel.onEvent(TransactionDetailEvent.SaveEdit)

            val saved = awaitItemWhere { it.editor == null && it.transaction!!.isEdited }
            assertEquals(46_050L, saved.transaction?.amountMinor)
            assertEquals(now, saved.transaction?.editedAt)
            // A real edit is both: the marker, and something reconciliation must see.
            assertEquals(now, saved.transaction?.touchedAt)
            assertFalse(loaded.transaction!!.isEdited)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding a note does not claim the amount was changed`() = runTest {
        // `editedAt` means "a human changed what the message said". A note changes
        // nothing the parser extracted, so the ledger must not mark the row.
        seedParsedTransaction()
        val viewModel = viewModel("t1")

        viewModel.state.test {
            awaitItemWhere { it.transaction != null }
            viewModel.onEvent(TransactionDetailEvent.StartEdit)
            val editor = awaitItemWhere { it.editor != null }.editor!!

            viewModel.onEvent(TransactionDetailEvent.EditorChanged(editor.copy(note = "market day")))
            viewModel.onEvent(TransactionDetailEvent.SaveEdit)

            val saved = awaitItemWhere { it.editor == null && it.transaction?.note != null }
            assertEquals("market day", saved.transaction?.note)
            assertNull(saved.transaction?.editedAt)
            // But it is still a touch. A note written inside an open period is a human
            // reaching into it, and reconciliation is entitled to know.
            assertEquals(now, saved.transaction?.touchedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an amount that will not parse is refused and the editor stays open`() = runTest {
        seedParsedTransaction()
        val viewModel = viewModel("t1")

        viewModel.state.test {
            awaitItemWhere { it.transaction != null }
            viewModel.onEvent(TransactionDetailEvent.StartEdit)
            val editor = awaitItemWhere { it.editor != null }.editor!!

            viewModel.onEvent(TransactionDetailEvent.EditorChanged(editor.copy(amountText = "abc")))
            viewModel.onEvent(TransactionDetailEvent.SaveEdit)

            val state = awaitItemWhere { it.editor?.amountIsInvalid == true }
            // Still editing, and the stored amount is untouched.
            assertEquals(45_000L, state.transaction?.amountMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `always use this category writes a rule the user could later read`() = runTest {
        seedParsedTransaction()
        val viewModel = viewModel("t1")

        viewModel.state.test {
            awaitItemWhere { it.transaction != null }
            viewModel.onEvent(
                TransactionDetailEvent.CategoryPicked("cat_sales", alwaysApply = true),
            )
            awaitItemWhere { it.transaction?.categoryId == "cat_sales" }
            cancelAndIgnoreRemainingEvents()
        }

        val rule = rules.getCategoryRules().single()
        assertEquals(MatchField.COUNTERPARTY_NAME, rule.matchField)
        assertEquals(MatchType.CONTAINS, rule.matchType)
        assertEquals("ABEBE", rule.pattern)
        assertEquals("cat_sales", rule.categoryId)
    }

    @Test
    fun `categorising once does not create a rule`() = runTest {
        seedParsedTransaction()
        val viewModel = viewModel("t1")

        viewModel.state.test {
            awaitItemWhere { it.transaction != null }
            viewModel.onEvent(
                TransactionDetailEvent.CategoryPicked("cat_sales", alwaysApply = false),
            )
            awaitItemWhere { it.transaction?.categoryId == "cat_sales" }
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(rules.getCategoryRules().isEmpty())
    }

    @Test
    fun `deleting is soft, and confirmed first`() = runTest {
        seedParsedTransaction()
        val viewModel = viewModel("t1")

        viewModel.state.test {
            awaitItemWhere { it.transaction != null }

            viewModel.onEvent(TransactionDetailEvent.RequestDelete)
            assertTrue(awaitItemWhere { it.isConfirmingDelete }.isConfirmingDelete)

            viewModel.onEvent(TransactionDetailEvent.ConfirmDelete)
            assertTrue(awaitItemWhere { it.isDeleted }.isDeleted)
            cancelAndIgnoreRemainingEvents()
        }

        // Soft: the row is still there, out of the ledger but not erased.
        assertNotNull(db.transactionDao().getById("t1"))
    }

    @Test
    fun `saying a flagged row is not a duplicate clears the flag and nothing else`() = runTest {
        seedParsedTransaction()
        db.transactionDao().insert(
            transaction("t2", rawMessageId = null, possibleDuplicateOfId = "t1"),
        )
        val viewModel = viewModel("t2")

        viewModel.state.test {
            val flagged = awaitItemWhere { it.transaction != null }
            assertTrue(flagged.transaction!!.isPossibleDuplicate)

            viewModel.onEvent(TransactionDetailEvent.DismissPossibleDuplicate)

            val cleared = awaitItemWhere { it.transaction?.isPossibleDuplicate == false }
            // No figure changed, so this is neither an edit nor a touch: a period being
            // reconciled has nothing new to explain.
            assertNull(cleared.transaction?.editedAt)
            assertNull(cleared.transaction?.touchedAt)
            assertEquals(45_000L, cleared.transaction?.amountMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel(transactionId: String) = TransactionDetailViewModel(
        transactions = transactions,
        rules = rules,
        customers = CustomerRepository(db.customerDao(), db.creditEntryDao()),
        ids = IdGenerator { "rule_1" },
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(mapOf("transactionId" to transactionId)),
    )

    private suspend fun seedParsedTransaction() {
        db.categoryDao().insert(category("cat_sales", name = "Sales"))
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage("raw1", sender = "127", body = "you have received ETB 450.00"),
        )
        db.transactionDao().insert(
            transaction(
                id = "t1",
                rawMessageId = "raw1",
                direction = Direction.IN,
                amountMinor = 45_000,
                counterpartyName = "ABEBE",
                categoryId = "cat_sales",
                occurredAt = T0,
            ),
        )
    }

    private suspend fun ReceiveTurbine<TransactionDetailUiState>.awaitItemWhere(
        predicate: (TransactionDetailUiState) -> Boolean,
    ): TransactionDetailUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
