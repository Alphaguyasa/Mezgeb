package et.alpha.mezgeb.ui.ledger

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.capture.ParserProvider
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.Dispatchers
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
import et.alpha.mezgeb.parser.model.Channel as EngineChannel

class LedgerViewModelTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = Instant.parse("2026-08-02T10:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private lateinit var settingsHarness: SettingsHarness
    private lateinit var transactions: TransactionRepository
    private lateinit var rules: RuleRepository
    private lateinit var coordinator: CaptureCoordinator

    /**
     * Built per test rather than in setup, so a test can seed the database first. The
     * ViewModel reads categories as soon as it exists.
     */
    private val viewModel: LedgerViewModel by lazy {
        LedgerViewModel(transactions, rules, coordinator, clock, zone)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsHarness = SettingsHarness(tempFolder.root)
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
        coordinator = CaptureCoordinator(
            transactions = transactions,
            rules = rules,
            parserProvider = object : ParserProvider {
                override suspend fun current() = MessageParser(TEST_RULES)
            },
            settings = settingsHarness.repository,
            ids = IdGenerator { "generated" },
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is filtered until the user filters it`() {
        // The initial state, asserted on the type rather than on a constructed
        // ViewModel: with an in-process database the load finishes before the first
        // read, so the loading state is not observable from a test.
        val initial = LedgerUiState()

        assertTrue(initial.isLoading)
        assertFalse(initial.isFiltered)
        assertEquals(DateRange.ALL, initial.dateRange)
        assertEquals(TransactionFilter(), initial.filter)
    }

    @Test
    fun `categories arrive for the filter bar to offer`() = runTest {
        db.categoryDao().insert(category("cat_sales", name = "Sales"))

        viewModel.state.test {
            val state = awaitItemWhere { it.categories.isNotEmpty() }
            assertFalse(state.isLoading)
            assertEquals("Sales", state.categories.single().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a date range becomes real bounds, in local time`() = runTest {
        viewModel.onEvent(LedgerEvent.DateRangeChanged(DateRange.TODAY))

        val today = MezgebDates.dayBounds(now, zone)
        val filter = viewModel.state.value.filter
        assertEquals(today.first, filter.from)
        assertEquals(today.last, filter.to)
        assertTrue(viewModel.state.value.isFiltered)
    }

    @Test
    fun `the last seven days includes today`() = runTest {
        viewModel.onEvent(LedgerEvent.DateRangeChanged(DateRange.LAST_7_DAYS))

        val today = MezgebDates.dayBounds(now, zone)
        val filter = viewModel.state.value.filter
        assertEquals(today.first - 6 * MezgebDates.DAY_MILLIS, filter.from)
        assertEquals(today.last, filter.to)
    }

    @Test
    fun `search text is visible immediately, whatever the debounce is doing`() = runTest {
        viewModel.onEvent(LedgerEvent.SearchChanged("abebe"))

        // The field must never lag behind the keyboard, even though the query itself
        // is debounced before it reaches the database.
        assertEquals("abebe", viewModel.state.value.searchQuery)
    }

    @Test
    fun `clearing filters clears the search box too`() = runTest {
        viewModel.onEvent(LedgerEvent.SearchChanged("abebe"))
        viewModel.onEvent(LedgerEvent.DateRangeChanged(DateRange.TODAY))
        viewModel.onEvent(
            LedgerEvent.FilterChanged(
                viewModel.state.value.filter.copy(
                    channel = Channel.CASH,
                    direction = Direction.OUT,
                ),
            ),
        )

        viewModel.onEvent(LedgerEvent.ClearFilters)

        val state = viewModel.state.value
        assertEquals("", state.searchQuery)
        assertEquals(DateRange.ALL, state.dateRange)
        assertEquals(TransactionFilter(), state.filter)
        assertFalse(state.isFiltered)
    }

    @Test
    fun `pull to refresh re-parses the review queue and says what changed`() = runTest {
        // A message that no rule could read when it arrived.
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(
                id = "m1",
                sender = "CBE",
                body = "Your account has been credited with ETB 450.00 today.",
                parseState = ParseState.UNMATCHED,
            ),
        )

        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(LedgerEvent.Refresh)

            val done = awaitItemWhere { !it.isRefreshing && it.lastReparse != null }
            assertEquals(1, done.lastReparse?.recorded)
            cancelAndIgnoreRemainingEvents()
        }

        // The message is now a transaction, and out of the queue.
        assertEquals(1, transactions.listTransactions(TransactionFilter()).size)
        assertEquals(ParseState.PARSED, db.rawMessageDao().getById("m1")?.parseState)
    }

    @Test
    fun `a refresh that changes nothing still reports back`() = runTest {
        viewModel.state.test {
            awaitItem()
            viewModel.onEvent(LedgerEvent.Refresh)

            val done = awaitItemWhere { it.lastReparse != null }
            assertEquals(0, done.lastReparse?.changed)

            // And the message is cleared once shown, so it does not reappear.
            viewModel.onEvent(LedgerEvent.ReparseResultShown)
            assertNull(awaitItemWhere { it.lastReparse == null }.lastReparse)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun ReceiveTurbine<LedgerUiState>.awaitItemWhere(
        predicate: (LedgerUiState) -> Boolean,
    ): LedgerUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }

    private companion object {
        val TEST_RULES = listOf(
            ParserRule(
                id = "cbe_credited",
                name = "CBE credited",
                senderPattern = "CBE",
                channel = EngineChannel.BANK,
                bodyPattern = "credited with ETB (?<amount>[\\d,.]+)",
                fieldMap = mapOf("amount" to SemanticField.AMOUNT),
                directionStrategy = DirectionStrategy.FIXED_IN,
            ),
        )
    }
}
