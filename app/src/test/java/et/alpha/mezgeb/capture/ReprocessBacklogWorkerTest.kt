package et.alpha.mezgeb.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The backlog re-run — what happens to the review queue after a rule is taught.
 *
 * This worker is half the value of teach-a-rule. Inducing a rule that only applies to
 * messages arriving from now on leaves the eight months of inbox a shop just imported
 * sitting exactly where they were, and the feature reads as broken by someone who
 * taught the app something and watched nothing happen.
 */
class ReprocessBacklogWorkerTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var settingsHarness: SettingsHarness
    private lateinit var settings: SettingsRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsHarness = SettingsHarness(tempFolder.root)
        settings = settingsHarness.repository
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
    }

    @After
    fun tearDown() = settingsHarness.close()

    @Test
    fun `a taught rule clears the messages that were waiting for it`() = runTest {
        queue("m1" to "credited with ETB 450.00", "m2" to "credited with ETB 12.50")

        val result = worker(rules = TEST_RULES).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, output.getInt(ReprocessBacklogWorker.KEY_SCANNED, -1))
        assertEquals(2, output.getInt(ReprocessBacklogWorker.KEY_RECORDED, -1))

        assertEquals(2, transactions.listTransactions(TransactionFilter()).size)
        // The queue is what the user is watching. It has to actually empty.
        assertTrue(db.rawMessageDao().getByParseState(ParseState.UNMATCHED).isEmpty())
    }

    @Test
    fun `a message no rule reads stays in the queue rather than being dropped`() = runTest {
        queue("m1" to "credited with ETB 450.00", "m2" to "something no rule matches")

        val result = worker(rules = TEST_RULES).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, output.getInt(ReprocessBacklogWorker.KEY_SCANNED, -1))
        assertEquals(1, output.getInt(ReprocessBacklogWorker.KEY_RECORDED, -1))

        // "We still cannot read this" is not the same as "this was never ours to keep".
        // The unreadable one is the review queue's whole reason to exist.
        val stillQueued = db.rawMessageDao().getByParseState(ParseState.UNMATCHED)
        assertEquals(listOf("m2"), stillQueued.map { it.id })
    }

    @Test
    fun `an empty queue succeeds without inventing work`() = runTest {
        val result = worker(rules = TEST_RULES).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(0, output.getInt(ReprocessBacklogWorker.KEY_SCANNED, -1))
        assertEquals(0, output.getInt(ReprocessBacklogWorker.KEY_RECORDED, -1))
        assertTrue(transactions.listTransactions(TransactionFilter()).isEmpty())
    }

    @Test
    fun `re-running after everything parsed does not duplicate the ledger`() = runTest {
        queue("m1" to "credited with ETB 450.00")

        worker(rules = TEST_RULES).doWork()
        // A second rule taught while the first pass was running appends another run,
        // so the worker is expected to be called again over a queue it already cleared.
        worker(rules = TEST_RULES).doWork()

        assertEquals(1, transactions.listTransactions(TransactionFilter()).size)
    }

    @Test
    fun `with no rules at all nothing is recorded and nothing is lost`() = runTest {
        queue("m1" to "credited with ETB 450.00")

        val result = worker(rules = emptyList()).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(0, output.getInt(ReprocessBacklogWorker.KEY_RECORDED, -1))
        assertEquals(1, db.rawMessageDao().getByParseState(ParseState.UNMATCHED).size)
    }

    @Test
    fun `the reported total matches what was actually scanned`() = runTest {
        queue(
            "m1" to "credited with ETB 450.00",
            "m2" to "credited with ETB 1.00",
            "m3" to "unreadable",
        )

        val output = (worker(rules = TEST_RULES).doWork() as ListenableWorker.Result.Success)
            .outputData

        // The screen renders "scanned of total". A total that disagrees with the count
        // shows a progress bar that never reaches its own end.
        assertEquals(
            output.getInt(ReprocessBacklogWorker.KEY_SCANNED, -1),
            output.getInt(ReprocessBacklogWorker.KEY_TOTAL, -2),
        )
    }

    /** Puts messages in the review queue, which is where this worker starts from. */
    private suspend fun queue(vararg bodies: Pair<String, String>) {
        db.rawMessageDao().insertAllIgnoringDuplicates(
            bodies.mapIndexed { index, (id, body) ->
                rawMessage(
                    id = id,
                    sender = "CBE",
                    body = body,
                    contentHash = "h$index",
                    parseState = ParseState.UNMATCHED,
                )
            },
        )
    }

    private fun coordinator(rules: List<ParserRule>) = CaptureCoordinator(
        transactions = transactions,
        rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao()),
        parserProvider = object : ParserProvider {
            override suspend fun current() = MessageParser(rules)
        },
        settings = settings,
        ids = object : IdGenerator {
            private var next = 0
            override fun newId() = "id_${++next}"
        },
        clock = Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC),
    )

    private fun worker(rules: List<ParserRule>): ReprocessBacklogWorker =
        TestListenableWorkerBuilder<ReprocessBacklogWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = ReprocessBacklogWorker(
                        context = appContext,
                        params = workerParameters,
                        coordinator = coordinator(rules),
                    )
                },
            )
            .build()

    private companion object {
        val TEST_RULES = listOf(
            ParserRule(
                id = "cbe_credited",
                name = "CBE credited",
                senderPattern = "CBE",
                channel = Channel.BANK,
                bodyPattern = "credited with ETB (?<amount>[\\d,.]+)",
                fieldMap = mapOf("amount" to SemanticField.AMOUNT),
                directionStrategy = DirectionStrategy.FIXED_IN,
            ),
        )
    }
}
