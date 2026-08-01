package et.alpha.mezgeb.parser.normalize

/**
 * Normalises Ethiopian mobile numbers to E.164.
 *
 * Ethiopian mobile prefixes are 09 and 07, where the leading 0 is the national trunk
 * prefix and is dropped in international form. `0912345678`, `+251912345678`,
 * `251912345678` and `912345678` are all the same subscriber and all normalise to
 * `+251912345678`.
 *
 * Landlines and short codes are deliberately not normalised: a payment counterparty is
 * a mobile subscriber, and turning a shortcode like `127` into a phone number would be
 * worse than leaving it alone.
 */
object PhoneNormalizer {

    private const val COUNTRY_CODE = "251"
    private const val NATIONAL_LENGTH = 9

    /** @return E.164, or null when [raw] is not a recognisable Ethiopian mobile number. */
    fun toE164(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val digits = GeezNumeralNormalizer.toArabicDigits(raw).filter { it.isDigit() }
        if (digits.isEmpty()) return null

        val national = when {
            // 251912345678
            digits.length == COUNTRY_CODE.length + NATIONAL_LENGTH && digits.startsWith(COUNTRY_CODE) ->
                digits.substring(COUNTRY_CODE.length)
            // 0912345678
            digits.length == NATIONAL_LENGTH + 1 && digits.startsWith("0") ->
                digits.substring(1)
            // 912345678
            digits.length == NATIONAL_LENGTH ->
                digits
            else -> return null
        }

        // Mobile only. After stripping the trunk prefix the number starts 9 or 7.
        if (national.firstOrNull() !in setOf('9', '7')) return null

        return "+$COUNTRY_CODE$national"
    }

    /** True when [raw] looks like something worth trying to normalise. */
    fun looksLikePhone(raw: String?): Boolean = toE164(raw) != null
}
