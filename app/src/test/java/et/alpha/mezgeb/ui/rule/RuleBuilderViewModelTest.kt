package et.alpha.mezgeb.ui.rule

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import et.alpha.mezgeb.capture.BacklogReprocessor
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.capture.ParserProvider
import et.alpha.mezgeb.capture.ReprocessStatus
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.mapper.toEngineRule
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The teach-a-rule flow, from tagged spans to a cleared backlog.
 *
 * The assertions worth reading are the last two: saving a rule has to clear the
 * messages already waiting, and recording one message by hand must not save a rule.
 * Those are the two promises the screen makes.
 */
class RuleBuilderViewModelTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = Instant.parse("2026-08-02T10:00:00Z").toEpochMilli()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var settingsHarness: SettingsHarness
    private lateinit var transactions: TransactionRepository
    private lateinit var rules: RuleRepository
    private lateinit var coordinator: CaptureCoordinator
    private val reprocessor = FakeReprocessor()

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
                override suspend fun current() = MessageParser(
                    rules = rules.getEnabledParserRulesBlockingForTest(json),
                )
            },
            settings = settingsHarness.repository,
            ids = SequentialIds(),
            clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the queue for this sender is what the user is shown`() = runTest {
        seedQueue()

        viewModel().state.test {
            val state = awaitItemWhere { it.messages.isNotEmpty() }
            assertEquals(3, state.messages.size)
            assertEquals("AWASH", state.sender)
            // Nothing is tagged, so there is nothing to preview and nothing to save.
            assertNull(state.preview)
            assertFalse(state.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tagging the amount produces a preview of what would be recorded`() = runTest {
        seedQueue()
        val viewModel = viewModel()

        viewModel.state.test {
            val loaded = awaitItemWhere { it.messages.isNotEmpty() }
            viewModel.onEvent(RuleBuilderEvent.TagSpan(SemanticField.AMOUNT, amountRange(loaded)))

            val tagged = awaitItemWhere { it.preview != null }
            assertEquals(45_000L, tagged.preview?.amountMinor)
            assertTrue(tagged.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `without an amount there is nothing to record and induction says so`() = runTest {
        seedQueue()
        val viewModel = viewModel()

        viewModel.state.test {
            val loaded = awaitItemWhere { it.messages.isNotEmpty() }
            viewModel.onEvent(
                RuleBuilderEvent.TagSpan(SemanticField.COUNTERPARTY_NAME, nameRange(loaded)),
            )

            val tagged = awaitItemWhere { it.inductionFailure != null }
            assertEquals(
                et.alpha.mezgeb.parser.induce.InductionFailure.NO_AMOUNT_SPAN,
                tagged.inductionFailure,
            )
            assertFalse(tagged.canSave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `testing the rule reports how many of the others it reads`() = runTest {
        seedQueue()
        val viewModel = viewModel()

        viewModel.state.test {
            val loaded = awaitItemWhere { it.messages.isNotEmpty() }
            viewModel.onEvent(RuleBuilderEvent.TagSpan(SemanticField.AMOUNT, amountRange(loaded)))
            awaitItemWhere { it.preview != null }

            viewModel.onEvent(RuleBuilderEvent.TestRule)

            val tested = awaitItemWhere { it.report != null }
            // Two others, same template, different amounts.
            assertEquals(2, tested.report?.matched)
            assertEquals(2, tested.report?.total)
            assertFalse(tested.report!!.looksTooSpecific)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a rule clears the messages that were already waiting`() = runTest {
        seedQueue()
        val viewModel = viewModel()

        viewModel.state.test {
            val loaded = awaitItemWhere { it.messages.isNotEmpty() }
            viewModel.onEvent(RuleBuilderEvent.TagSpan(SemanticField.AMOUNT, amountRange(loaded)))
            awaitItemWhere { it.preview != null }

            viewModel.onEvent(RuleBuilderEvent.SaveRule)

            val saved = awaitItemWhere { it.savedRuleResolved != null }
            // All three, including the one it was taught from. This is the promise.
            assertEquals(3, saved.savedRuleResolved)
            cancelAndIgnoreRemainingEvents()
        }

        val stored = rules.getEnabledParserRules().single()
        assertTrue("a taught rule is never overwritten by an update", stored.isUserCreated)
        assertNotNull(stored.toEngineRule(json))

        assertEquals(3, transactions.listTransactions(TransactionFilter()).size)
        assertEquals(0, transactions.getByParseState(ParseState.UNMATCHED).size)
        assertTrue("the backlog worker is asked to finish the rest", reprocessor.started)
    }

    @Test
    fun `recording one message by hand does not save a rule`() = runTest {
        seedQueue()
        val viewModel = viewModel()

        viewModel.state.test {
            val loaded = awaitItemWhere { it.messages.isNotEmpty() }
            viewModel.onEvent(RuleBuilderEvent.TagSpan(SemanticField.AMOUNT, amountRange(loaded)))
            awaitItemWhere { it.preview != null }

            viewModel.onEvent(RuleBuilderEvent.ResolveManually)

            assertTrue(awaitItemWhere { it.resolvedManually }.resolvedManually)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue("no rule should have been saved", rules.getEnabledParserRules().isEmpty())
        assertEquals(1, transactions.listTransactions(TransactionFilter()).size)
        // The other two are still waiting: one message was resolved, not a template.
        assertEquals(2, transactions.getByParseState(ParseState.UNMATCHED).size)
        assertEquals(
            ParseState.RESOLVED_MANUALLY,
            transactions.getRawMessage("m1")?.parseState,
        )
    }

    @Test
    fun `changing example clears spans drawn on the previous one`() = runTest {
        // Offsets belong to the text they were drawn on; carrying them over would tag
        // whatever happened to sit at those characters in a different message.
        seedQueue()
        val viewModel = viewModel()

        viewModel.state.test {
            val loaded = awaitItemWhere { it.messages.isNotEmpty() }
            viewModel.onEvent(RuleBuilderEvent.TagSpan(SemanticField.AMOUNT, amountRange(loaded)))
            awaitItemWhere { it.preview != null }

            viewModel.onEvent(RuleBuilderEvent.ExampleChanged(1))

            val switched = awaitItemWhere { it.exampleIndex == 1 }
            assertTrue(switched.spans.isEmpty())
            assertNull(switched.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fixtures ----------------------------------------------------------------------

    private fun viewModel(rawMessageId: String? = null) = RuleBuilderViewModel(
        transactions = transactions,
        rules = rules,
        coordinator = coordinator,
        reprocessor = reprocessor,
        ids = IdGenerator { "rule_1" },
        json = json,
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        savedStateHandle = SavedStateHandle(
            mapOf("sender" to "AWASH", "rawMessageId" to rawMessageId),
        ),
    )

    private suspend fun seedQueue() {
        listOf(
            "m1" to "Dear customer, ETB 450.00 has been credited to your account from ABEBE.",
            "m2" to "Dear customer, ETB 12.50 has been credited to your account from KEBEDE.",
            "m3" to "Dear customer, ETB 3,000.00 has been credited to your account from CHALTU.",
        ).forEachIndexed { index, (id, body) ->
            db.rawMessageDao().insertIgnoringDuplicates(
                rawMessage(
                    id = id,
                    sender = "AWASH",
                    body = body,
                    contentHash = "hash_$id",
                    // Descending, so the queue's newest-first order puts m1 first and
                    // the spans below are drawn on the message the screen shows.
                    receivedAt = T0 - index * 1000L,
                    parseState = ParseState.UNMATCHED,
                ),
            )
        }
    }

    /** Where the amount sits in the shown message, as a user's selection would be. */
    private fun amountRange(state: RuleBuilderUiState): IntRange = rangeOf(state, "450.00")

    private fun nameRange(state: RuleBuilderUiState): IntRange = rangeOf(state, "ABEBE")

    private fun rangeOf(state: RuleBuilderUiState, text: String): IntRange {
        val body = state.example!!.body
        val start = body.indexOf(text)
        require(start >= 0) { "'$text' is not in the message the screen is showing: $body" }
        return IntRange(start, start + text.length - 1)
    }

    private class FakeReprocessor : BacklogReprocessor {
        var started = false
        override fun start() {
            started = true
        }

        override fun observe(): Flow<ReprocessStatus> = MutableStateFlow(ReprocessStatus.Idle)
    }

    private class SequentialIds : IdGenerator {
        private var next = 0
        override fun newId(): String = "id_${++next}"
    }

    private suspend fun ReceiveTurbine<RuleBuilderUiState>.awaitItemWhere(
        predicate: (RuleBuilderUiState) -> Boolean,
    ): RuleBuilderUiState {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}

/**
 * The coordinator needs the rules as engine rules; the repository hands out domain
 * ones. Test-only bridge, so the fake parser provider stays a one-liner.
 */
private suspend fun RuleRepository.getEnabledParserRulesBlockingForTest(json: Json) =
    getEnabledParserRules().mapNotNull { it.toEngineRule(json) }
