package et.alpha.mezgeb.capture

/**
 * Whether capture is actually working.
 *
 * On many of the devices this app is built for, the vendor's battery manager kills
 * notification listeners silently. The app keeps running, the ledger keeps looking
 * fine, and payments stop arriving — a failure that is invisible until the shop
 * reconciles at the end of the month and the numbers are short. This state exists so
 * that failure is visible on the Today screen the same day.
 */
enum class CaptureHealth {
    HEALTHY,

    /** Notification access was never granted, or has been revoked. */
    NO_ACCESS,

    /** Access is granted but nothing has arrived for longer than is plausible. */
    SILENT_TOO_LONG;

    companion object {

        /**
         * Two days. Long enough that a slow Sunday, a public holiday or a closed shop
         * does not cry wolf; short enough that a listener killed on Monday is
         * questioned before the week is out.
         */
        const val DEFAULT_SILENCE_THRESHOLD_MILLIS: Long = 48L * 60 * 60 * 1000

        /**
         * @param lastCaptureAt when the listener last delivered a message, or null if
         *   it never has.
         * @param since when the app first ran. Silence is measured from here while
         *   [lastCaptureAt] is null, so a listener that has never worked once is not
         *   mistaken for a shop that has had no payments yet.
         */
        fun evaluate(
            hasNotificationAccess: Boolean,
            lastCaptureAt: Long?,
            since: Long,
            now: Long,
            silenceThresholdMillis: Long = DEFAULT_SILENCE_THRESHOLD_MILLIS,
        ): CaptureHealth = when {
            !hasNotificationAccess -> NO_ACCESS
            now - (lastCaptureAt ?: since) > silenceThresholdMillis -> SILENT_TOO_LONG
            else -> HEALTHY
        }
    }
}
