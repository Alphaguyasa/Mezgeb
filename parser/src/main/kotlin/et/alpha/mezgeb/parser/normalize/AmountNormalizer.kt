package et.alpha.mezgeb.parser.normalize

/**
 * Turns the amount text a message carries into `Long` santim.
 *
 * Parsing is string manipulation from start to finish. `(500.10 * 100).toLong()` is
 * 50009 on some inputs, and a ledger that is off by a santim in a way nobody notices
 * until it matters is exactly the failure this app exists to prevent.
 *
 * Handles, from the observed corpus and the formats in the spec:
 * `500`, `500.5`, `500.50`, `1,234.56`, `1 234.56`, `1234.56`, `ETB 1,234.56`,
 * `1,234.56 ETB`, `Birr 500`, `ብር 500`, `ETB27.00`, and `2000.0.` where a sentence
 * period follows the amount.
 */
object AmountNormalizer {

    private val CURRENCY_TOKENS = listOf("ETB", "BIRR", "ብር", "BR")

    /** Digits, separators and at most one decimal part. */
    private val AMOUNT_PATTERN = Regex("""\d[\d,\s ]*(?:\.\d+)?""")

    /**
     * @return santim, or null when [raw] carries no parseable amount.
     */
    fun toMinor(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null

        // Geez numerals first: the digit patterns below only know Arabic digits.
        var text = GeezNumeralNormalizer.toArabicDigits(raw).trim()

        // Strip the currency token wherever it sits, prefix or suffix.
        for (token in CURRENCY_TOKENS) {
            text = text.replace(token, " ", ignoreCase = true)
        }

        val match = AMOUNT_PATTERN.find(text) ?: return null
        var candidate = match.value.trim()

        // "2000.0." — a sentence period rides along on the capture. Strip trailing
        // separators so the fractional part is not left dangling.
        candidate = candidate.trimEnd('.', ',', ' ', ' ')
        if (candidate.isEmpty()) return null

        val decimalIndex = candidate.lastIndexOf('.')
        val wholePart: String
        val fractionPart: String
        if (decimalIndex >= 0) {
            wholePart = candidate.substring(0, decimalIndex)
            fractionPart = candidate.substring(decimalIndex + 1)
        } else {
            wholePart = candidate
            fractionPart = ""
        }

        val wholeDigits = wholePart.filter { it.isDigit() }
        if (wholeDigits.isEmpty() && fractionPart.isEmpty()) return null

        // Pad or truncate the fraction to exactly two digits. "500.5" is 50050, not
        // 50005 — a single decimal place means tenths of a birr.
        val fractionDigits = fractionPart.filter { it.isDigit() }
        val santimPart = when {
            fractionDigits.isEmpty() -> "00"
            fractionDigits.length == 1 -> fractionDigits + "0"
            else -> fractionDigits.substring(0, 2)
        }

        val whole = if (wholeDigits.isEmpty()) 0L else wholeDigits.toLongOrNull() ?: return null
        val santim = santimPart.toLongOrNull() ?: return null
        return whole * 100 + santim
    }

    /**
     * Finds every amount-like run in [text], in order.
     *
     * Used by rule induction to offer candidate spans, never by the engine — a rule
     * says which amount it means through its own capture group, because a message
     * routinely carries the transaction amount, a fee, a total and a balance.
     */
    fun findAll(text: String): List<Long> {
        val normalised = GeezNumeralNormalizer.toArabicDigits(text)
        return AMOUNT_PATTERN.findAll(normalised)
            .mapNotNull { toMinor(it.value) }
            .toList()
    }
}
