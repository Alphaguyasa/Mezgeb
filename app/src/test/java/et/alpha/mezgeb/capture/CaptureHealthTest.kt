package et.alpha.mezgeb.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureHealthTest {

    @Test
    fun `no access beats everything else`() {
        val health = CaptureHealth.evaluate(
            hasNotificationAccess = false,
            lastCaptureAt = NOW - MINUTE,
            since = NOW - MINUTE,
            now = NOW,
        )

        assertEquals(CaptureHealth.NO_ACCESS, health)
    }

    @Test
    fun `a recent capture is healthy`() {
        val health = CaptureHealth.evaluate(
            hasNotificationAccess = true,
            lastCaptureAt = NOW - HOUR,
            since = NOW - 30 * DAY,
            now = NOW,
        )

        assertEquals(CaptureHealth.HEALTHY, health)
    }

    @Test
    fun `silence past the threshold is silence, however long the app has run`() {
        val health = CaptureHealth.evaluate(
            hasNotificationAccess = true,
            lastCaptureAt = NOW - 3 * DAY,
            since = NOW - 30 * DAY,
            now = NOW,
        )

        assertEquals(CaptureHealth.SILENT_TOO_LONG, health)
    }

    @Test
    fun `a freshly installed app that has captured nothing is not yet a problem`() {
        val health = CaptureHealth.evaluate(
            hasNotificationAccess = true,
            lastCaptureAt = null,
            since = NOW - HOUR,
            now = NOW,
        )

        assertEquals(CaptureHealth.HEALTHY, health)
    }

    @Test
    fun `a listener that has never once delivered is eventually a problem`() {
        // The case that would otherwise hide: access granted, nothing ever arrives,
        // and with no first-run stamp there would be nothing to measure silence from.
        val health = CaptureHealth.evaluate(
            hasNotificationAccess = true,
            lastCaptureAt = null,
            since = NOW - 5 * DAY,
            now = NOW,
        )

        assertEquals(CaptureHealth.SILENT_TOO_LONG, health)
    }

    @Test
    fun `the boundary is not yet an alarm`() {
        val health = CaptureHealth.evaluate(
            hasNotificationAccess = true,
            lastCaptureAt = NOW - CaptureHealth.DEFAULT_SILENCE_THRESHOLD_MILLIS,
            since = NOW - 30 * DAY,
            now = NOW,
        )

        assertEquals(CaptureHealth.HEALTHY, health)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val NOW = 1_785_000_000_000L
    }
}
