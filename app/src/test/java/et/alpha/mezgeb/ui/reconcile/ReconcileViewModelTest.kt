package et.alpha.mezgeb.ui.reconcile

import app.cash.turbine.ReceiveTurbine
import android.database.sqlite.SQLiteException
import app.cash.turbine.test
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.LedgerChanges
import et.alpha.mezgeb.data.repo.ReconciliationRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.domain.reconcile.ExpectedSalesProvider
import et.alpha.mezgeb.domain.reconcile.GapExplanation
import et.alpha.mezgeb.domain.reconcile.ManualEntryExpectedSalesProvider
import et.alpha.mezgeb.domain.reconcile.ReconcilePeriodUseCase
import et.alpha.mezgeb.util.IdGenerator
import et.alpha.mezgeb.util.MezgebDates
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ReconcileViewModelTest : DatabaseTest() {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Mid-afternoon, so "today" has room either side of it locally. */
    private val now = Instant.parse("2026-08-02T12:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    private lateinit var sessions: ReconciliationRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var countingExpectedSales: CountingExpectedSales
    private lateinit var viewModel: ReconcileViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        sessions = ReconciliationRepository(db.reconciliationSessionDao())
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        countingExpectedSales = CountingExpectedSales(ManualEntryExpectedSalesProvider(sessions))
        viewModel = newViewModel()
    }

    private fun newViewModel(
        repository: ReconciliationRepository = sessions,
        // Sequential, not fixed: one test opens a second period after closing the
        // first, and two sessions cannot share a primary key.
        ids: IdGenerator = object : IdGenerator {
            private var next = 0
            override fun newId() = "session_${++next}"
        },
    ) = ReconcileViewModel(
        sessions = repository,
        reconcile = ReconcilePeriodUseCase(
            transactions = transactions,
            customers = CustomerRepository(db.customerDao(), db.creditEntryDao()),
            expectedSales = countingExpectedSales,
        ),
        ledgerChanges = LedgerChanges(db),
        ids = ids,
        clock = clock,
        zone = zone,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with no open period the picker is what is shown`() = runTest {
        viewModel.state.test {
            val state = awaitItemWhere { !it.isLoading }
            assertFalse(state.hasOpenPeriod)
            assertNull(state.reconciliation)
            assertEquals(PeriodChoice.TODAY, state.periodChoice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening today's period records when it was opened, not when it started`() = runTest {
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)

            val open = awaitItemWhere { it.hasOpenPeriod }.openSession!!
            assertEquals(MezgebDates.dayBounds(now, zone).first, open.periodStart)
            // The distinction that makes "edited since opened" mean anything.
            assertEquals(now, open.openedAt)
            assertEquals(SessionStatus.OPEN, open.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a custom period cannot be opened until dates are chosen`() = runTest {
        viewModel.onEvent(ReconcileEvent.PeriodChosen(PeriodChoice.CUSTOM))
        viewModel.state.test {
            val state = awaitItemWhere { it.periodChoice == PeriodChoice.CUSTOM }
            assertNull(state.customRange)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.onEvent(ReconcileEvent.OpenPeriod)

        // Opening anyway would invent a boundary nobody agreed to.
        assertNull(sessions.getOpenSession())
    }

    @Test
    fun `a second Open before the first is written opens one period`() = runTest {
        // A queued Main, so both taps arrive before the first write has run: the
        // double tap the button used to allow.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        viewModel.onEvent(ReconcileEvent.OpenPeriod)
        viewModel.onEvent(ReconcileEvent.OpenPeriod)
        advanceUntilIdle()

        assertEquals(1, openRows())
    }

    @Test
    fun `Open with a period already open writes nothing and reports nothing`() = runTest {
        viewModel.onEvent(ReconcileEvent.OpenPeriod)
        // A second screen, or a tap that lands after the first period was written.
        val other = newViewModel(ids = object : IdGenerator {
            override fun newId() = "other"
        })
        other.state.test {
            awaitItemWhere { !it.isLoading }
            other.onEvent(ReconcileEvent.OpenPeriod)
            val state = awaitItemWhere { it.hasOpenPeriod && !it.isBusy }
            // The period already open is the one shown; being refused is not an error.
            assertEquals("session_1", state.openSession?.id)
            assertNull(state.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, openRows())
    }

    @Test
    fun `a failed Open is reported and the button works again`() = runTest {
        closeTodayExpecting("100.00")
        // The id of the period just closed, so the insert hits the primary key and
        // takes the path a genuine write failure would.
        val failing = newViewModel(ids = object : IdGenerator {
            override fun newId() = "session_1"
        })
        failing.state.test {
            awaitItemWhere { !it.isLoading }
            failing.onEvent(ReconcileEvent.OpenPeriod)
            val state = awaitItemWhere { it.errorMessage != null }
            assertEquals(ReconcileUiState.ErrorKind.SAVE_FAILED, state.errorMessage)
            assertFalse(state.isBusy)
            assertTrue(state.canOpen)

            failing.onEvent(ReconcileEvent.ErrorShown)
            assertNull(awaitItemWhere { it.errorMessage == null }.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, openRows())
    }

    @Test
    fun `a failed expected total is reported and what was typed stays`() = runTest {
        val repository = spyk(sessions)
        coEvery { repository.update(any()) } throws SQLiteException("disk I/O error")
        val failing = newViewModel(repository = repository)
        failing.state.test {
            awaitItemWhere { !it.isLoading }
            failing.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            failing.onEvent(ReconcileEvent.ExpectedChanged("400.00"))
            failing.onEvent(ReconcileEvent.SaveExpected)
            val state = awaitItemWhere { it.errorMessage != null }
            assertEquals(ReconcileUiState.ErrorKind.SAVE_FAILED, state.errorMessage)
            assertEquals("400.00", state.expectedInput)
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(sessions.getOpenSession()?.expectedTotalMinor)
    }

    @Test
    fun `a failed close keeps the period open and the note typed`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        val repository = spyk(sessions)
        coEvery { repository.closeSession(any()) } throws SQLiteException("disk I/O error")
        val failing = newViewModel(repository = repository)
        failing.state.test {
            awaitItemWhere { !it.isLoading }
            failing.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }
            failing.onEvent(ReconcileEvent.ExpectedChanged("400.00"))
            failing.onEvent(ReconcileEvent.SaveExpected)
            awaitItemWhere { it.reconciliation?.gapMinor != null }
            failing.onEvent(ReconcileEvent.NoteChanged("gave change from my pocket"))

            failing.onEvent(ReconcileEvent.ClosePeriod)
            val state = awaitItemWhere { it.errorMessage != null }
            assertEquals(ReconcileUiState.ErrorKind.SAVE_FAILED, state.errorMessage)
            assertTrue(state.hasOpenPeriod)
            assertEquals("gave change from my pocket", state.noteInput)
            assertNull(state.justClosedId)
            // Close can be tried again.
            assertTrue(state.canClose)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(SessionStatus.OPEN, sessions.getOpenSession()?.status)
    }

    @Test
    fun `stating what you expected produces a gap`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("400.00"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)

            val state = awaitItemWhere { it.reconciliation?.gapMinor != null }
            assertEquals(30_000L, state.reconciliation?.accountedForMinor)
            assertEquals(10_000L, state.reconciliation?.gapMinor)
            assertTrue(state.reconciliation!!.isShort)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an expected total that is not a number is refused`() = runTest {
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("not money"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)

            assertTrue(awaitItemWhere { it.expectedIsInvalid }.expectedIsInvalid)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(sessions.getOpenSession()?.expectedTotalMinor)
    }

    @Test
    fun `a balanced period says so`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("300.00"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)

            assertTrue(awaitItemWhere { it.isBalanced }.isBalanced)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Closing, and what closing means ------------------------------------------------

    @Test
    fun `closing snapshots every figure into the row`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        closeTodayExpecting("400.00")

        val closed = firstClosedSession()

        assertEquals(SessionStatus.CLOSED, closed.status)
        assertEquals(now, closed.closedAt)
        assertEquals(20_000L, closed.capturedInMinor)
        assertEquals(10_000L, closed.cashRecordedMinor)
        assertEquals(40_000L, closed.expectedTotalMinor)
    }

    @Test
    fun `editing a transaction inside a closed period does not change the closed figures`() =
        runTest {
            // The whole point of closing a period. A month later, the numbers a shop
            // owner looked at and agreed with have to still be those numbers.
            seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
            closeTodayExpecting("400.00")

            val before = firstClosedSession()

            // Someone corrects a captured amount after the fact.
            val edited = transactions.getTransaction("captured")!!
            transactions.update(
                edited.copy(amountMinor = 99_000, editedAt = now + 1, touchedAt = now + 1),
            )

            val after = firstClosedSession()
            assertEquals(before.capturedInMinor, after.capturedInMinor)
            assertEquals(20_000L, after.capturedInMinor)
            assertEquals(before.expectedTotalMinor, after.expectedTotalMinor)
        }

    @Test
    fun `an edit inside a closed period surfaces in the next period instead`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        closeTodayExpecting("400.00")

        val edited = transactions.getTransaction("captured")!!
        transactions.update(
            edited.copy(amountMinor = 99_000, editedAt = now + 1, touchedAt = now + 1),
        )

        // The next period covers the same day and is opened after the edit.
        viewModel.state.test {
            awaitItemWhere { !it.hasOpenPeriod }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)

            val explanations = awaitItemWhere {
                it.reconciliation?.explanations?.isNotEmpty() == true
            }.reconciliation!!.explanations

            assertNotNull(
                "the edit has to show up somewhere, and this is where",
                explanations.filterIsInstance<GapExplanation.EditedSincePeriodOpened>()
                    .firstOrNull(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a closed period can be read back exactly as it was closed`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        closeTodayExpecting("400.00")

        viewModel.state.test {
            val state = awaitItemWhere { it.closedSessions.isNotEmpty() }
            val session = state.closedSessions.single()

            viewModel.onEvent(ReconcileEvent.ViewSnapshot(session))
            assertEquals(session, awaitItemWhere { it.viewingSnapshot != null }.viewingSnapshot)

            viewModel.onEvent(ReconcileEvent.DismissSnapshot)
            assertNull(awaitItemWhere { it.viewingSnapshot == null }.viewingSnapshot)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- The note on a closed period ---------------------------------------------------

    @Test
    fun `a period that closes with a gap keeps the owner's note, trimmed`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        closeTodayExpecting("400.00", note = "  gave 100 change from my own pocket \n")

        assertEquals("gave 100 change from my own pocket", firstClosedSession().note)
    }

    @Test
    fun `a blank note is kept as no note`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        closeTodayExpecting("400.00", note = "   ")

        assertNull(firstClosedSession().note)
    }

    @Test
    fun `a balanced period asks for no note and keeps none`() = runTest {
        // Typed while there was a gap, then the gap closed: the field is gone, so
        // whatever was in it must not be saved out of sight.
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("400.00"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)
            assertTrue(awaitItemWhere { it.reconciliation?.gapMinor != null }.canAddNote)
            viewModel.onEvent(ReconcileEvent.NoteChanged("short by 100"))

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("300.00"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)
            assertFalse(awaitItemWhere { it.isBalanced }.canAddNote)

            viewModel.onEvent(ReconcileEvent.ClosePeriod)
            awaitItemWhere { it.closedSessions.isNotEmpty() }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(firstClosedSession().note)
    }

    @Test
    fun `a note longer than the limit is cut as it is typed`() = runTest {
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.NoteChanged("x".repeat(NOTE_MAX_LENGTH + 50)))

            assertEquals(
                NOTE_MAX_LENGTH,
                awaitItemWhere { it.noteInput.isNotEmpty() }.noteInput.length,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the note is cleared once its period has closed`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        closeTodayExpecting("400.00", note = "short by 100")

        viewModel.state.test {
            assertEquals("", awaitItemWhere { it.closedSessions.isNotEmpty() }.noteInput)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fresh figures (R29) --------------------------------------------------------------

    @Test
    fun `a payment captured while the period is open moves the gap`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("300.00"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)
            awaitItemWhere { it.isBalanced }

            captureLatePayment(5_000)

            val state = awaitItemWhere { it.reconciliation?.capturedInMinor == 25_000L }
            assertEquals(-5_000L, state.reconciliation?.gapMinor)
            assertFalse(state.isBalanced)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `closing right after a payment is captured snapshots that payment`() = runTest {
        // The screen showed a balanced period. A payment lands, and the owner taps
        // Close before anything else happens. The closed row must not say balanced.
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged("300.00"))
            viewModel.onEvent(ReconcileEvent.SaveExpected)
            awaitItemWhere { it.isBalanced }

            captureLatePayment(5_000)
            viewModel.onEvent(ReconcileEvent.ClosePeriod)
            awaitItemWhere { it.closedSessions.isNotEmpty() }
            cancelAndIgnoreRemainingEvents()
        }

        val closed = firstClosedSession()
        assertEquals(25_000L, closed.capturedInMinor)
        assertEquals(30_000L, closed.expectedTotalMinor)
        val accountedFor = closed.capturedInMinor + closed.cashRecordedMinor +
            closed.creditGivenMinor - closed.creditRepaidMinor
        assertEquals(35_000L, accountedFor)
    }

    @Test
    fun `typing a note or an expected total does not recompute the figures`() = runTest {
        seedToday(capturedInMinor = 20_000, cashMinor = 10_000)
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.reconciliation != null }

            val before = countingExpectedSales.calls
            "short by 100".forEachIndexed { i, _ ->
                viewModel.onEvent(ReconcileEvent.NoteChanged("short by 100".take(i + 1)))
            }
            "400.00".forEachIndexed { i, _ ->
                viewModel.onEvent(ReconcileEvent.ExpectedChanged("400.00".take(i + 1)))
            }
            awaitItemWhere { it.expectedInput == "400.00" }

            assertEquals(before, countingExpectedSales.calls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Fixtures -----------------------------------------------------------------------

    /** A telebirr payment in today's period, inserted the way capture inserts one. */
    private suspend fun captureLatePayment(amountMinor: Long) {
        val bounds = MezgebDates.dayBounds(now, zone)
        db.transactionDao().insert(
            transaction(
                "late",
                channel = Channel.TELEBIRR,
                direction = Direction.IN,
                amountMinor = amountMinor,
                categoryId = "cat_sales",
                occurredAt = bounds.first + 3,
            ),
        )
    }

    /** Counts how often the figures are computed: the use case asks this once per run. */
    private class CountingExpectedSales(
        private val delegate: ExpectedSalesProvider,
    ) : ExpectedSalesProvider {
        var calls = 0
            private set

        override suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long): Long? {
            calls++
            return delegate.expectedTotalMinor(periodStart, periodEnd)
        }
    }

    private suspend fun seedToday(capturedInMinor: Long, cashMinor: Long) {
        val bounds = MezgebDates.dayBounds(now, zone)
        db.transactionDao().insertAll(
            listOf(
                transaction(
                    "captured",
                    channel = Channel.TELEBIRR,
                    direction = Direction.IN,
                    amountMinor = capturedInMinor,
                    categoryId = "cat_sales",
                    occurredAt = bounds.first + 1,
                ),
                transaction(
                    "cash",
                    channel = Channel.CASH,
                    direction = Direction.IN,
                    amountMinor = cashMinor,
                    categoryId = "cat_sales",
                    occurredAt = bounds.first + 2,
                ),
            ),
        )
    }

    private suspend fun closeTodayExpecting(expected: String, note: String? = null) {
        viewModel.state.test {
            awaitItemWhere { !it.isLoading }
            viewModel.onEvent(ReconcileEvent.OpenPeriod)
            awaitItemWhere { it.hasOpenPeriod }

            viewModel.onEvent(ReconcileEvent.ExpectedChanged(expected))
            viewModel.onEvent(ReconcileEvent.SaveExpected)
            awaitItemWhere { it.reconciliation?.expectedTotalMinor != null }
            note?.let { viewModel.onEvent(ReconcileEvent.NoteChanged(it)) }

            viewModel.onEvent(ReconcileEvent.ClosePeriod)
            awaitItemWhere { it.closedSessions.isNotEmpty() }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun openRows(): Int =
        db.reconciliationSessionDao().getAll().count { it.status == SessionStatus.OPEN }

    private suspend fun firstClosedSession(): et.alpha.mezgeb.domain.model.ReconciliationSession {
        var result: et.alpha.mezgeb.domain.model.ReconciliationSession? = null
        sessions.observeClosedSessions().test { result = awaitItem().first() }
        return result!!
    }

    private suspend fun <T> ReceiveTurbine<T>.awaitItemWhere(predicate: (T) -> Boolean): T {
        var item = awaitItem()
        while (!predicate(item)) item = awaitItem()
        return item
    }
}
