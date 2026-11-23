package et.alpha.mezgeb.domain.reconcile

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.navigation.LaunchRequest
import et.alpha.mezgeb.navigation.MezgebDestination
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Shadows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The evening closing check.
 *
 * Like the weekly reminder, most of these tests are about when it keeps quiet: on a
 * clean day, before evening, a second time the same evening, and when the shop has
 * switched reminders off. A notification that speaks out of turn is one the owner
 * turns off, and then the habit it was meant to build is gone too.
 */
class ClosingCheckWorkerTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.UTC
    private val evening = Instant.parse("2026-08-01T20:30:00Z").toEpochMilli()
    private val morning = Instant.parse("2026-08-01T09:00:00Z").toEpochMilli()
    private val earlyToday = Instant.parse("2026-08-01T08:00:00Z").toEpochMilli()
    private val yesterday = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()

    private lateinit var context: Context
    private lateinit var settingsHarness: SettingsHarness
    private lateinit var settings: SettingsRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settingsHarness = SettingsHarness(tempFolder.root)
        settings = settingsHarness.repository
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
    }

    @After
    fun tearDown() = settingsHarness.close()

    @Test
    fun `money the ledger cannot explain today is named with what was recorded`() = runTest {
        db.transactionDao().insert(transaction("t1", amountMinor = 300_000, occurredAt = earlyToday))
        unreadToday(body = "you have received ETB 200.00")

        val result = worker(now = evening).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val notification = single()
        assertEquals(
            "Closing check: 200.00 ETB not explained today",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(body, body.contains("3,000.00 ETB"))
        assertTrue(body, body.contains("200.00 ETB"))
    }

    @Test
    fun `yesterday's unread message is not today's problem`() = runTest {
        db.rawMessageDao().insertAllIgnoringDuplicates(
            listOf(rawMessage("old", parseState = ParseState.UNMATCHED, receivedAt = yesterday)),
        )

        worker(now = evening).doWork()

        assertEquals(0, postedNotifications())
    }

    @Test
    fun `a clean day says nothing but counts as checked`() = runTest {
        db.transactionDao().insert(transaction("t1", occurredAt = earlyToday))

        worker(now = evening).doWork()

        // "All good" every night is noise; silence is the reward for a clean day.
        assertEquals(0, postedNotifications())
        assertEquals(Instant.ofEpochMilli(evening).atZone(zone).toLocalDate().toEpochDay(), settings.closingCheckDay())
    }

    @Test
    fun `a run that lands before evening does nothing and leaves the day open`() = runTest {
        unreadToday()

        worker(now = morning).doWork()

        // The day is not over at nine in the morning. The evening run still owns it.
        assertEquals(0, postedNotifications())
        assertNull(settings.closingCheckDay())
    }

    @Test
    fun `a second run the same evening stays quiet`() = runTest {
        worker(now = evening).doWork()
        unreadToday()

        worker(now = evening + 30 * 60_000).doWork()

        assertEquals(0, postedNotifications())
    }

    @Test
    fun `reminders switched off cover the closing check too`() = runTest {
        settings.setReconcileRemindersEnabled(false)
        unreadToday()

        val result = worker(now = evening).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `without notification permission it succeeds silently`() = runTest {
        Shadows.shadowOf(context as Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        unreadToday()

        val result = worker(now = evening).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `a possible double count with no missing money gives no made-up figure`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("a", amountMinor = 45_000, occurredAt = earlyToday),
                transaction("b", amountMinor = 45_000, occurredAt = earlyToday + 30_000, possibleDuplicateOfId = "a"),
            ),
        )

        worker(now = evening).doWork()

        assertEquals(
            "Closing check: something to look at today",
            single().extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
    }

    @Test
    fun `tapping it opens Reconcile through an immutable intent`() = runTest {
        unreadToday()

        worker(now = evening).doWork()

        val contentIntent = single().contentIntent
        assertNotNull(contentIntent)
        val shadow = Shadows.shadowOf(contentIntent)
        assertTrue(shadow.isActivityIntent)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(MezgebDestination.Reconcile, LaunchRequest.destinationFor(shadow.savedIntent))
    }

    @Test
    fun `the first run is aimed at the next eight o'clock in the evening`() {
        fun delayAt(time: String) = ClosingCheckWorker.delayUntilClosing(Instant.parse(time), zone)

        assertEquals(Duration.ofHours(1), delayAt("2026-08-01T19:00:00Z"))
        // At eight exactly, today's slot has just passed, so it is tomorrow's.
        assertEquals(Duration.ofHours(24), delayAt("2026-08-01T20:00:00Z"))
        assertEquals(Duration.ofHours(23), delayAt("2026-08-01T21:00:00Z"))
    }

    private suspend fun unreadToday(body: String = "you have received ETB 450.00") {
        db.rawMessageDao().insertAllIgnoringDuplicates(
            listOf(rawMessage("m1", body = body, parseState = ParseState.UNMATCHED, receivedAt = earlyToday)),
        )
    }

    private fun manager() =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun postedNotifications(): Int = Shadows.shadowOf(manager()).size()

    private fun single(): Notification = Shadows.shadowOf(manager()).allNotifications.single()

    private fun worker(now: Long): ClosingCheckWorker =
        TestListenableWorkerBuilder<ClosingCheckWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = ClosingCheckWorker(
                        context = appContext,
                        params = workerParameters,
                        reconcile = ReconcilePeriodUseCase(
                            transactions = transactions,
                            customers = CustomerRepository(db.customerDao(), db.creditEntryDao()),
                            expectedSales = object : ExpectedSalesProvider {
                                override suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long): Long? = null
                            },
                        ),
                        settings = settings,
                        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
                        zone = zone,
                    )
                },
            )
            .build()
}
