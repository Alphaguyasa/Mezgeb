package et.alpha.mezgeb.capture

import android.database.sqlite.SQLiteFullException
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.di.AppModule
import et.alpha.mezgeb.domain.model.CaptureSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * ROADMAP R48: a failure in application-scoped work is counted, and the process lives.
 *
 * The scope under test is the one [AppModule] builds, so the test covers the real
 * wiring and not a copy of it. Without the handler, `runTest` reports the exception
 * as uncaught and the test fails, which is how the crash shows up off a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundFailureLogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = Instant.parse("2026-08-01T09:30:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private lateinit var settingsHarness: SettingsHarness

    @Before
    fun setUp() {
        settingsHarness = SettingsHarness(tempFolder.root)
    }

    @After
    fun tearDown() {
        settingsHarness.close()
    }

    @Test
    fun `a capture that throws crashes nothing and is counted`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val log = BackgroundFailureLog(settingsHarness.repository, clock, dispatcher)
        val scope = AppModule.provideApplicationScope(dispatcher, log)
        // The failure the roadmap item names: a cheap phone with its storage full.
        val coordinator = mockk<CaptureCoordinator>()
        coEvery { coordinator.capture(any()) } throws SQLiteFullException("database or disk is full")

        scope.launch { coordinator.capture(message()) }
        scope.launch { coordinator.capture(message()) }

        val failures = settingsHarness.repository.observeBackgroundFailures().first { it?.count == 2 }
        assertEquals(now, failures?.lastAt)

        // The scope is still usable: the next notification is still handed off.
        assertTrue(scope.isActive)
        var ranAfter = false
        scope.launch { ranAfter = true }
        assertTrue(ranAfter)
        scope.cancel()
    }

    @Test
    fun `no failure means no notice`() = runTest {
        assertNull(settingsHarness.repository.observeBackgroundFailures().first())
    }

    @Test
    fun `clearing the count hides the notice until the next failure`() = runTest {
        val settings = settingsHarness.repository
        settings.recordBackgroundFailure(at = 1_000L)
        settings.recordBackgroundFailure(at = 2_000L)
        assertEquals(2, settings.observeBackgroundFailures().first()?.count)

        settings.clearBackgroundFailures()
        assertNull(settings.observeBackgroundFailures().first())

        // A new failure starts again from one, at its own time.
        settings.recordBackgroundFailure(at = 3_000L)
        val after = settings.observeBackgroundFailures().first()
        assertEquals(1, after?.count)
        assertEquals(3_000L, after?.lastAt)
    }

    private fun message() = CapturedMessage(
        sender = "127",
        body = "You have received ETB 450.00",
        receivedAt = now,
        source = CaptureSource.NOTIFICATION,
    )
}
