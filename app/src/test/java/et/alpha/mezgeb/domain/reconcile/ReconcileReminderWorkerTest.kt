package et.alpha.mezgeb.domain.reconcile

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.data.repo.ReconciliationRepository
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.SessionStatus
import et.alpha.mezgeb.util.MezgebDates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.Shadows
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The reconciliation nudge.
 *
 * Every test here is really about restraint. This is the one part of the app that
 * speaks without being spoken to, and the failure mode is not a crash — it is a shop
 * owner turning notifications off for good, which costs the app the one habit it is
 * trying to build. So: only when a week has actually passed, never on a fresh install,
 * and silence when the user has said no.
 */
class ReconcileReminderWorkerTest : DatabaseTest() {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var settingsHarness: SettingsHarness
    private lateinit var settings: SettingsRepository
    private lateinit var sessions: ReconciliationRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application)
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        settingsHarness = SettingsHarness(tempFolder.root)
        settings = settingsHarness.repository
        sessions = ReconciliationRepository(db.reconciliationSessionDao())
    }

    @After
    fun tearDown() = settingsHarness.close()

    @Test
    fun `a week without reconciling earns one notification`() = runTest {
        settings.markFirstRun(T0)
        closedPeriod(closedAt = T0)

        val result = worker(now = T0 + 8 * DAY).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(1, postedNotifications())
    }

    @Test
    fun `six days is not a week and says nothing`() = runTest {
        settings.markFirstRun(T0)
        closedPeriod(closedAt = T0)

        worker(now = T0 + 6 * DAY).doWork()

        // The boundary matters more than it looks: a reminder that fires early once
        // fires early every time, and daily nagging is how notifications get disabled.
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `the seventh day is the day it fires`() = runTest {
        settings.markFirstRun(T0)
        closedPeriod(closedAt = T0)

        worker(now = T0 + REMIND_AFTER_DAYS * DAY).doWork()

        // "After a week" is ambiguous by one day and the code has to pick a side. It
        // compares with `<`, so the window closes the instant the seventh day is
        // reached rather than a day later. Pinned here so the choice is deliberate —
        // one day either way is invisible in review and obvious to whoever gets the
        // notification.
        assertEquals(1, postedNotifications())
    }

    @Test
    fun `one millisecond short of seven days still says nothing`() = runTest {
        settings.markFirstRun(T0)
        closedPeriod(closedAt = T0)

        worker(now = T0 + REMIND_AFTER_DAYS * DAY - 1).doWork()

        assertEquals(0, postedNotifications())
    }

    @Test
    fun `reminders switched off stay off however long it has been`() = runTest {
        settings.markFirstRun(T0)
        settings.setReconcileRemindersEnabled(false)
        closedPeriod(closedAt = T0)

        val result = worker(now = T0 + 400 * DAY).doWork()

        // Success, not failure: the user said no, and that is an answer rather than
        // an error for WorkManager to retry.
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `a fresh install with no first-run timestamp is left alone`() = runTest {
        // Nothing recorded at all — no first run, no closed period. There is no
        // "since" to measure from, and guessing would mean nagging a shop that has
        // not yet had a chance to do anything.
        val result = worker(now = T0 + 400 * DAY).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `never having reconciled measures from first run, not from nothing`() = runTest {
        settings.markFirstRun(T0)

        worker(now = T0 + 8 * DAY).doWork()

        // No closed period exists, so the fallback is what fires. A new install is not
        // nagged on day one and is not left alone forever either.
        assertEquals(1, postedNotifications())
    }

    @Test
    fun `the clock runs from the most recent close, not the first one`() = runTest {
        settings.markFirstRun(T0)
        closedPeriod(id = "old", closedAt = T0, periodEnd = T0)
        closedPeriod(id = "recent", closedAt = T0 + 30 * DAY, periodEnd = T0 + 30 * DAY)

        worker(now = T0 + 33 * DAY).doWork()

        // Thirty-three days since the first close but only three since the last, and
        // it is the last that counts. Measuring from the first would nag a shop that
        // reconciles every week, forever.
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `without notification permission it succeeds silently`() = runTest {
        Shadows.shadowOf(context as Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.markFirstRun(T0)

        val result = worker(now = T0 + 400 * DAY).doWork()

        // A refused permission is the user's answer, not a failure to retry.
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, postedNotifications())
    }

    @Test
    fun `below Android 13 the reminder needs no runtime permission`() = runTest {
        // POST_NOTIFICATIONS does not exist before API 33, so the platform answers
        // "denied" to every check of it. Before ROADMAP R6 the worker took that answer
        // at face value and the reminder never appeared on Android 8 to 12. The test
        // runtime is API 34, so the older version is simulated by setting SDK_INT.
        Shadows.shadowOf(context as Application)
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.markFirstRun(T0)

        val realSdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
        try {
            worker(now = T0 + 8 * DAY).doWork()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", realSdk)
        }

        assertEquals(1, postedNotifications())
    }

    private fun postedNotifications(): Int {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return Shadows.shadowOf(manager).size()
    }

    private suspend fun closedPeriod(
        id: String = "s1",
        closedAt: Long,
        periodEnd: Long = closedAt,
    ) {
        db.reconciliationSessionDao().insert(
            ReconciliationSessionEntity(
                id = id,
                periodStart = periodEnd - DAY,
                periodEnd = periodEnd,
                openedAt = periodEnd - DAY,
                expectedTotalMinor = 40_000,
                capturedInMinor = 20_000,
                capturedOutMinor = 0,
                cashRecordedMinor = 10_000,
                creditGivenMinor = 0,
                creditRepaidMinor = 0,
                status = SessionStatus.CLOSED,
                closedAt = closedAt,
                note = null,
            ),
        )
    }

    private fun worker(now: Long): ReconcileReminderWorker =
        TestListenableWorkerBuilder<ReconcileReminderWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = ReconcileReminderWorker(
                        context = appContext,
                        params = workerParameters,
                        sessions = sessions,
                        settings = settings,
                        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
                    )
                },
            )
            .build()

    private companion object {
        const val DAY = MezgebDates.DAY_MILLIS
        const val REMIND_AFTER_DAYS = ReconcileReminderWorker.REMIND_AFTER_DAYS
    }
}
