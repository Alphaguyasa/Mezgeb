package et.alpha.mezgeb.util

/**
 * The only sanctioned way to describe a captured message in a log line.
 *
 * .md hard constraint #5: raw message bodies never leave the device and are
 * never logged. That constraint is easy to hold in review and easy to break at 2am
 * while chasing a capture bug, so the safe alternative exists as a named utility
 * rather than as a rule people are expected to remember.
 *
 * Sender and length are safe: they identify which template misbehaved without
 * carrying an amount, a name or a phone number.
 */
object Redactor {

    /** A one-line, body-free description of a message. Safe to log. */
    fun describe(sender: String, body: String): String =
        "sender=${sender.take(MAX_SENDER)} bodyChars=${body.length}"

    /** Masks all but the last two digits. For phones, account and reference numbers. */
    fun tail(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        if (value.length <= KEEP) return "*".repeat(value.length)
        return "*".repeat(value.length - KEEP) + value.takeLast(KEEP)
    }

    private const val MAX_SENDER = 24
    private const val KEEP = 2
}
