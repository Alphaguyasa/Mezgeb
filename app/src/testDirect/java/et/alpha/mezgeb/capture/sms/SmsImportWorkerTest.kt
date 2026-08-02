package et.alpha.mezgeb.capture.sms

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.capture.CapturedMessage
import et.alpha.mezgeb.capture.ParserProvider
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Shadows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The import loop: what it reads, what it refuses to store, and where it leaves the
 * cursor.
 *
 * The assertion that matters most is the one about the personal message. A phone's
 * inbox is mostly private correspondence, and this app has no business keeping any
 * of it — so the allowlist runs before the funnel, and a message from a friend is
 * read from the cursor and dropped without ever reaching the database.
 */
class SmsImportWorkerTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var settingsHarness: SettingsHarness
    private lateinit var settings: SettingsRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var coordinator: CaptureCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(Manifest.permission.READ_SMS)

        settingsHarness = SettingsHarness(tempFolder.root)
        settings = settingsHarness.repository
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        coordinator = CaptureCoordinator(
            transactions = transactions,
            rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao()),
            parserProvider = object : ParserProvider {
                override suspend fun current() = MessageParser(TEST_RULES)
            },
            settings = settings,
            ids = object : IdGenerator {
                private var next = 0
                override fun newId() = "id_${++next}"
            },
            clock = Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() {
        settingsHarness.close()
    }

    @Test
    fun `payments are imported and personal messages are never stored`() = runTest {
        val source = FakeInboxSource(
            row("CBE", "Your account has been credited with ETB 450.00", T0),
            row("+251911000001", "See you at the shop tomorrow", T0 + 1000),
            row("CBE", "Your account has been credited with ETB 12.50", T0 + 2000),
        )

        val result = worker(source).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(2, output.getInt(SmsImportWorker.KEY_IMPORTED, -1))
        assertEquals(3, output.getInt(SmsImportWorker.KEY_SCANNED, -1))

        assertEquals(2, transactions.listTransactions(TransactionFilter()).size)
        // Three rows were read; only the two from a financial sender exist anywhere.
        assertEquals(2, db.rawMessageDao().getByParseState(ParseState.PARSED).size)
    }

    @Test
    fun `a payment already captured live is not imported twice`() = runTest {
        val body = "Your account has been credited with ETB 450.00"
        coordinator.capture(
            CapturedMessage(
                sender = "CBE",
                body = body,
                receivedAt = T0,
                source = CaptureSource.NOTIFICATION,
            ),
        )

        val result = worker(FakeInboxSource(row("CBE", body, T0 + 380))).doWork()

        val output = (result as ListenableWorker.Result.Success).outputData
        assertEquals(0, output.getInt(SmsImportWorker.KEY_IMPORTED, -1))
        assertEquals(1, output.getInt(SmsImportWorker.KEY_DUPLICATES, -1))
        assertEquals(1, transactions.listTransactions(TransactionFilter()).size)
    }

    @Test
    fun `the cursor advances so a second import re-reads nothing`() = runTest {
        val source = FakeInboxSource(
            row("CBE", "Your account has been credited with ETB 450.00", T0),
            row("CBE", "Your account has been credited with ETB 12.50", T0 + 9_000),
        )

        worker(source).doWork()

        assertEquals(T0 + 9_000, settings.observeSmsImportSince().first())
    }

    @Test
    fun `without the permission the import fails rather than half-runs`() = runTest {
        Shadows.shadowOf(context as android.app.Application)
            .denyPermissions(Manifest.permission.READ_SMS)

        val result = worker(FakeInboxSource(row("CBE", "credited with ETB 1.00", T0))).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(transactions.listTransactions(TransactionFilter()).isEmpty())
    }

    private fun worker(source: InboxSource): SmsImportWorker =
        TestListenableWorkerBuilder<SmsImportWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SmsImportWorker(
                        context = appContext,
                        params = workerParameters,
                        reader = source,
                        coordinator = coordinator,
                        settings = settings,
                        clock = Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC),
                    )
                },
            )
            .build()

    private fun row(address: String, body: String, date: Long) = InboxRow(address, body, date)

    private class FakeInboxSource(vararg rows: InboxRow) : InboxSource {
        private val rows = rows.toList()

        override fun count(since: Long): Int = rows.count { it.date > since }

        override fun page(since: Long, offset: Int, limit: Int): List<InboxRow> = rows
            .filter { it.date > since }
            .sortedBy { it.date }
            .drop(offset)
            .take(limit)
    }

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
