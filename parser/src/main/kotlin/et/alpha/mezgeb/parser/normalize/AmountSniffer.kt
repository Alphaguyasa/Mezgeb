package et.alpha.mezgeb.parser.normalize

/**
 * A guess at the amount in a message no rule could read.
 *
 * Deliberately not part of the parser. Nothing this produces ever becomes a
 * transaction — the whole design of the engine is that it declines rather than
 * guesses. This exists for one narrower job: reconciliation needs to rank the
 * unreadable messages in a period by how much of a gap they might account for, and
 * "14 messages, roughly 3,200 birr between them" is far more useful to a shop owner
 * than "14 messages".
 *
 * So: a hint, shown as a hint, never stored.
 */
object AmountSniffer {

    /**
     * The first currency-shaped amount in [body], in santim, or null.
     *
     * Anchored to a currency word rather than taking the first number, because
     * messages are full of numbers that are not money — account numbers, dates,
     * reference codes, percentages.
     */
    fun sniff(body: String): Long? {
        val match = CURRENCY_ANCHORED.find(body) ?: return null
        val digits = match.groupValues[BEFORE_AMOUNT].ifEmpty { match.groupValues[AFTER_AMOUNT] }
        return AmountNormalizer.toMinor(digits)
    }

    /** Total of the first amount found in each body. Bodies with none contribute zero. */
    fun sniffTotal(bodies: List<String>): Long = bodies.sumOf { sniff(it) ?: 0L }

    /** How many of [bodies] carried something amount-shaped at all. */
    fun sniffedCount(bodies: List<String>): Int = bodies.count { sniff(it) != null }

    /** `ETB 450.00`, `ETB450`, `450.00 birr`, `ብር 450` — in either order. */
    private val CURRENCY_ANCHORED = Regex(
        """(?:ETB|BIRR|ብር)\s*([\d,]+(?:\.\d{1,2})?)""" +
            """|([\d,]+(?:\.\d{1,2})?)\s*(?:ETB|BIRR|ብር)""",
        RegexOption.IGNORE_CASE,
    )

    private const val BEFORE_AMOUNT = 1
    private const val AFTER_AMOUNT = 2
}
