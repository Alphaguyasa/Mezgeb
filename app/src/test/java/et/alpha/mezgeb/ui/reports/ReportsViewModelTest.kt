package et.alpha.mezgeb.ui.reports

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import et.alpha.mezgeb.data.local.DAY
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.ReconciliationRepository
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ReconciliationSession
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.domain.report.BuildReportUseCase
import et.alpha.mezgeb.domain.report.PdfExporter
import et.alpha.mezgeb.domain.report.ReportPeriod
import et.alpha.mezgeb.util.MezgebDates
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = Instant.parse("2026-08-02T12:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private lateinit var settingsHarness: SettingsHarness
    private lateinit var transactions: TransactionRepository
    private lateinit var customers: CustomerRepository
    private lateinit var rules: RuleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsHarness = SettingsHarness(tempFolder.root)
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        customers = CustomerRepository(db.customerDao(), db.creditEntryDao())
        rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
    }

    @After
    fun tearDown() {
        settingsHarness.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an empty ledger does not pretend to have a summary`() = runTest {
        viewModel().state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertFalse(state.hasEnoughData)
            assertEquals(0, state.report?.transactionCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the week's figures reach the screen`() = runTest {
        seedWeek()

        viewModel().state.test {
            val state = awaitItemWhere { it.report?.transactionCount ?: 0 > 0 }
            assertTrue(state.hasEnoughData)
            assertEquals(3, state.report?.transactionCount)
            assertEquals(80_000L, state.report?.totalInMinor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `choosing today narrows the period`() = runTest {
        seedWeek()
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItemWhere { it.report != null }
            viewModel.onEvent(ReportsEvent.PeriodChosen(ReportPeriod.DAY))

            // The chip changes before the recomputed report arrives, so wait for the
            // state where both agree. The summary card labels itself from the report
            // rather than from the chip, so what is on screen is never mismatched.
            val expectedStart = MezgebDates.dayBounds(now, zone).first
            val state = awaitItemWhere {
                it.period == ReportPeriod.DAY && it.report?.periodStart == expectedStart
            }
            assertEquals(expectedStart, state.report?.periodStart)
            assertEquals(1, state.report?.transactionCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the suggested file name matches what is being exported`() {
        val viewModel = viewModel()

        assertTrue(viewModel.suggestedFileName(ExportKind.LEDGER_CSV).endsWith(".csv"))
        assertTrue(viewModel.suggestedFileName(ExportKind.SUMMARY_PDF).endsWith(".pdf"))
        assertEquals("text/csv", viewModel.mimeType(ExportKind.BOTH_CSV))
        assertEquals(PdfExporter.MIME_TYPE, viewModel.mimeType(ExportKind.SUMMARY_PDF))
    }

    @Test
    fun `exporting the ledger writes a csv the user could open`() = runTest {
        seedWeek()
        val viewModel = viewModel()
        val output = ByteArrayOutputStream()

        viewModel.state.test {
            awaitItemWhere { it.report != null }
            viewModel.export(ExportKind.LEDGER_CSV, "content://test/ledger.csv") { output }

            val state = awaitItemWhere { it.exported != null }
            assertEquals("content://test/ledger.csv", state.exported?.uri)
            cancelAndIgnoreRemainingEvents()
        }

        val csv = output.toString(Charsets.UTF_8)
        assertTrue("the BOM is what makes Excel read Amharic", csv.startsWith("﻿"))
        assertTrue(csv.contains("date,direction,amount"))
        // Three transactions plus a header.
        assertEquals(4, csv.trim().lines().size)
    }

    @Test
    fun `exporting the credit book writes the customers' entries`() = runTest {
        db.customerDao().insert(customer("c1", name = "Abebe"))
        db.creditEntryDao().insert(
            creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 50_000, occurredAt = now - DAY),
        )
        val viewModel = viewModel()
        val output = ByteArrayOutputStream()

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.export(ExportKind.CREDIT_CSV, "content://test/credit.csv") { output }
            awaitItemWhere { it.exported != null }
            cancelAndIgnoreRemainingEvents()
        }

        val csv = output.toString(Charsets.UTF_8)
        assertTrue(csv.contains("Abebe"))
        assertTrue(csv.contains("CREDIT_GIVEN"))
    }

    @Test
    fun `exporting periods writes the closed ones, oldest first, and not the open one`() = runTest {
        val repository = ReconciliationRepository(db.reconciliationSessionDao())
        val older = now - 30 * DAY
        val newer = now - 14 * DAY
        // Inserted newest first, so the order in the file is the exporter's doing.
        repository.openSession(period("newer", newer, SessionStatus.OPEN))
        repository.closeSession(period("newer", newer, SessionStatus.CLOSED, note = "second"))
        repository.openSession(period("older", older, SessionStatus.OPEN))
        repository.closeSession(period("older", older, SessionStatus.CLOSED, note = "first"))
        repository.openSession(period("open", now - DAY, SessionStatus.OPEN, note = "still open"))
        val viewModel = viewModel()
        val output = ByteArrayOutputStream()

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.export(ExportKind.PERIODS_CSV, "content://test/periods.csv") { output }
            awaitItemWhere { it.exported != null }
            cancelAndIgnoreRemainingEvents()
        }

        val rows = output.toString(Charsets.UTF_8).trim().lines().drop(1)
        assertEquals(listOf("first", "second"), rows.map { it.substringAfterLast(',') })
        assertTrue(viewModel.suggestedFileName(ExportKind.PERIODS_CSV).endsWith(".csv"))
        assertEquals("text/csv", viewModel.mimeType(ExportKind.PERIODS_CSV))
    }

    @Test
    fun `a failed write is reported rather than swallowed`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            // The picker can hand back a uri that cannot be opened — a removed SD card,
            // a provider that has since died.
            viewModel.export(ExportKind.LEDGER_CSV, "content://test/nowhere.csv") { null }

            assertEquals(
                ReportsUiState.ErrorKind.EXPORT_FAILED,
                awaitItemWhere { it.errorMessage != null }.errorMessage,
            )
            assertNull(viewModel.state.value.exported)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel() = ReportsViewModel(
        buildReport = BuildReportUseCase(transactions, customers, rules, zone),
        transactions = transactions,
        customers = customers,
        rules = rules,
        reconciliation = ReconciliationRepository(db.reconciliationSessionDao()),
        settings = settingsHarness.repository,
        pdfExporter = PdfExporter(ApplicationProvider.getApplicationContext<Context>(), zone),
        clock = clock,
        zone = zone,
    )

    private fun period(
        id: String,
        start: Long,
        status: SessionStatus,
        note: String? = null,
    ) = ReconciliationSession(
        id = id,
        periodStart = start,
        periodEnd = start + 7 * DAY,
        openedAt = start,
        expectedTotalMinor = 10_000,
        capturedInMinor = 10_000,
        capturedOutMinor = 0,
        cashRecordedMinor = 0,
        creditGivenMinor = 0,
        creditRepaidMinor = 0,
        status = status,
        closedAt = if (status == SessionStatus.CLOSED) start + 7 * DAY else null,
        note = note,
    )

    private suspend fun seedWeek() {
        db.categoryDao().insert(category("cat_sales", name = "Sales"))
        val today = MezgebDates.dayBounds(now, zone).first
        db.transactionDao().insertAll(
            listOf(
                transaction("a", direction = Direction.IN, amountMinor = 45_000, categoryId = "cat_sales", occurredAt = today + 1),
                transaction("b", direction = Direction.IN, amountMinor = 35_000, occurredAt = today - DAY),
                transaction("c", direction = Direction.OUT, amountMinor = 12_000, occurredAt = today - 2 * DAY),
            ),
        )
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
