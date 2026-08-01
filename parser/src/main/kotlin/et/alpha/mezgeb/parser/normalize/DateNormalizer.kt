package et.alpha.mezgeb.parser.normalize

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Turns a timestamp found in a message body into epoch millis UTC.
 *
 * Ethiopian providers send local time with no zone marker, so a bare timestamp is
 * interpreted as EAT (UTC+3) and converted. Storage is always UTC; the UI converts
 * back for display.
 *
 * Note these are Gregorian dates as printed by the provider. The Ethiopian calendar
 * appears in some promotional messages but not in the transaction templates observed,
 * so no conversion is attempted — inventing one would risk silently shifting a
 * transaction by seven or eight years.
 */
object DateNormalizer {

    /** East Africa Time. No DST. */
    val EAT: ZoneOffset = ZoneOffset.ofHours(3)

    private val DATE_TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yy HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "dd-MM-yyyy HH:mm:ss",
        "dd.MM.yyyy HH:mm:ss",
        "MMM d, yyyy h:mm:ss a",
        "MMM d, yyyy H:mm:ss",
    )

    private val DATE_ONLY_PATTERNS = listOf(
        "yyyy-MM-dd",
        "dd/MM/yyyy",
        "dd/MM/yy",
        "dd-MM-yyyy",
        "dd.MM.yyyy",
    )

    /**
     * @param raw text captured by the rule's timestamp group, if any.
     * @param receivedAt fallback: when the body carries no usable timestamp, the
     *   moment the message arrived is the best evidence available.
     * @return epoch millis UTC.
     */
    fun toEpochMillis(raw: String?, receivedAt: Long): Long {
        val parsed = parse(raw)
        return parsed ?: receivedAt
    }

    /** @return epoch millis UTC, or null when [raw] carries no parseable timestamp. */
    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = GeezNumeralNormalizer.toArabicDigits(raw)
            .trim()
            // CBE writes "29/06/2026 at 19:00:33"; the separator carries no information.
            .replace(Regex("""\s+at\s+""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")

        // An explicit offset in the text wins over any assumption.
        runCatching { OffsetDateTime.parse(text) }
            .onSuccess { return it.toInstant().toEpochMilli() }

        for (pattern in DATE_TIME_PATTERNS) {
            val formatter = formatterFor(pattern)
            try {
                val local = LocalDateTime.parse(text, formatter)
                return local.toInstant(EAT).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next pattern.
            }
        }

        for (pattern in DATE_ONLY_PATTERNS) {
            val formatter = formatterFor(pattern)
            try {
                val date = LocalDate.parse(text, formatter)
                return date.atStartOfDay().toInstant(EAT).toEpochMilli()
            } catch (_: DateTimeParseException) {
                // Try the next pattern.
            }
        }

        return null
    }

    private fun formatterFor(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)
}
