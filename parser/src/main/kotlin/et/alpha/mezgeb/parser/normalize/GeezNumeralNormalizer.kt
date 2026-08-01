package et.alpha.mezgeb.parser.normalize

/**
 * Converts Ethiopic (Geez) numerals to Arabic digits.
 *
 * Geez numerals are additive-multiplicative rather than positional: ፻ (100) and ፲፻
 * (10,000) act as multipliers on what precedes them, and the remaining symbols add.
 * So ፳፻፴፬ is (20 × 100) + 30 + 4 = 2034.
 *
 * There is no Geez zero, and no fractional notation — a decimal part is written with
 * Arabic digits even in otherwise-Geez text, so mixed strings are normal and this
 * leaves non-Geez characters untouched.
 *
 * **No corpus coverage.** No sample collected so far uses Geez numerals for an amount,
 * so this is specified behaviour verified by unit tests rather than by fixtures. See
 * CORPUS.md.
 */
object GeezNumeralNormalizer {

    private const val TEN_THOUSAND = '፼' // ፼
    private const val HUNDRED = '፻' // ፻

    /** Unit and tens digits: ፩–፱ then ፲–፺. */
    private val UNITS: Map<Char, Int> = mapOf(
        '፩' to 1, '፪' to 2, '፫' to 3, '፬' to 4, '፭' to 5,
        '፮' to 6, '፯' to 7, '፰' to 8, '፱' to 9,
        '፲' to 10, '፳' to 20, '፴' to 30, '፵' to 40, '፶' to 50,
        '፷' to 60, '፸' to 70, '፹' to 80, '፺' to 90,
    )

    fun isGeezNumeral(ch: Char): Boolean =
        UNITS.containsKey(ch) || ch == HUNDRED || ch == TEN_THOUSAND

    /**
     * Replaces every run of Geez numerals in [text] with its Arabic-digit value,
     * leaving everything else exactly as it was.
     */
    fun toArabicDigits(text: String): String {
        if (text.none { isGeezNumeral(it) }) return text

        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (!isGeezNumeral(text[index])) {
                out.append(text[index])
                index++
                continue
            }
            val start = index
            while (index < text.length && isGeezNumeral(text[index])) index++
            out.append(parseRun(text.substring(start, index)))
        }
        return out.toString()
    }

    /**
     * Evaluates one run of Geez numerals.
     *
     * `group` accumulates the value being multiplied by the next ፻ or ፼; `total`
     * accumulates everything already multiplied out.
     */
    private fun parseRun(run: String): String {
        var total = 0L
        var group = 0L

        for (ch in run) {
            when (ch) {
                HUNDRED -> {
                    // A bare ፻ means 100, not 0 × 100.
                    group = (if (group == 0L) 1L else group) * 100
                }
                TEN_THOUSAND -> {
                    total = (if (total + group == 0L) 1L else total + group) * 10_000
                    group = 0
                }
                else -> group += UNITS[ch]?.toLong() ?: 0L
            }
        }
        return (total + group).toString()
    }
}
