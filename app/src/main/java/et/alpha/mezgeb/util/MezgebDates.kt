package et.alpha.mezgeb.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place epoch millis become a day, and the one place a day boundary is
 * decided.
 *
 * Timestamps are stored as epoch millis UTC everywhere (CONVENTIONS.md), but a shop's day
 * is a local one: a payment at nine in the evening in Addis belongs to that evening,
 * not to the following morning in UTC. Every conversion from an instant to a day
 * happens here so that boundary is applied identically by the Today totals, the
 * ledger's day headers and, later, reconciliation periods.
 *
 * The calendar is Gregorian. Ethiopian calendar dates are a real and reasonable thing
 * for a shop to want and are not in scope for this app.
 */
object MezgebDates {

    /** Local midnight at the start of the day containing [epochMillis]. */
    fun startOfDay(epochMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** The last millisecond of that local day. Inclusive, matching the DAO's `<=`. */
    fun endOfDay(epochMillis: Long, zone: ZoneId): Long =
        startOfDay(epochMillis, zone) + DAY_MILLIS - 1

    /** Inclusive bounds of the local day containing [epochMillis]. */
    fun dayBounds(epochMillis: Long, zone: ZoneId): LongRange =
        startOfDay(epochMillis, zone)..endOfDay(epochMillis, zone)

    /**
     * How a day header should read. Resolved to a string by the UI, because "Today"
     * and "Yesterday" are translated words and this object holds no resources.
     */
    fun relativeDay(epochMillis: Long, now: Long, zone: ZoneId): RelativeDay {
        val day = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> RelativeDay.TODAY
            today.minusDays(1) -> RelativeDay.YESTERDAY
            else -> RelativeDay.EARLIER
        }
    }

    /** "Sat, 1 Jul" — or the same in the device's language, without the year. */
    fun formatDay(epochMillis: Long, zone: ZoneId, locale: Locale): String =
        dayFormatter(locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "Sat, 1 Jul 2026". Used where a date can be far enough back to be ambiguous. */
    fun formatFullDate(epochMillis: Long, zone: ZoneId, locale: Locale): String =
        fullDateFormatter(locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** 24-hour, because a receipt time is read, not spoken. */
    fun formatTime(epochMillis: Long, zone: ZoneId, locale: Locale): String =
        timeFormatter(locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** The local date, for anything that needs to compare days rather than instants. */
    fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /**
     * Formatters are built once per pattern and locale, then reused.
     *
     * `DateTimeFormatter.ofPattern` parses its pattern string and builds a printer
     * every time it is called, which is fine once and expensive once per row: the
     * ledger asks for a time label for every visible transaction on every scroll frame.
     * Found by reading the scroll path rather than by profiling — the number this costs
     * on a real low-end device has not been measured.
     *
     * A map rather than a single cached value because the app can change language at
     * runtime, and a one-entry cache would thrash on the frame after it did. Bounded in
     * practice by the number of locales the app ships, which is two.
     */
    private val formatters = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    private fun formatter(pattern: String, locale: Locale): DateTimeFormatter =
        formatters.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

    private fun dayFormatter(locale: Locale) = formatter("EEE, d MMM", locale)

    private fun fullDateFormatter(locale: Locale) = formatter("EEE, d MMM yyyy", locale)

    private fun timeFormatter(locale: Locale) = formatter("HH:mm", locale)

    const val DAY_MILLIS: Long = 24 * 60 * 60 * 1000L
}

enum class RelativeDay {
    TODAY,
    YESTERDAY,
    EARLIER,
}
