package et.alpha.mezgeb.util

import kotlin.math.absoluteValue

/**
 * The single place where `Long` santim becomes a display string.
 *
 * Money is stored and computed as minor units everywhere else in the app
 * (.md hard constraint #3); nothing outside the UI layer should call this.
 *
 * Formatting is done by string manipulation rather than by handing a scaled
 * `Double` to a [java.text.NumberFormat], for the same reason parsing is:
 * floating point cannot be trusted with money, and the failure mode is a ledger
 * that is off by a santim in a way nobody notices until it matters.
 */
object MoneyFormatter {

    const val DEFAULT_CURRENCY: String = "ETB"

    private const val GROUP_SEPARATOR = ','
    private const val DECIMAL_SEPARATOR = '.'

    /**
     * @param amountMinor the amount in santim.
     * @param currency currency code to append, or null to omit it.
     * @param signed when true, prefixes an explicit `+` or `−`. Money lists use this
     *   so direction survives greyscale printing and colour blindness.
     */
    fun format(
        amountMinor: Long,
        currency: String? = DEFAULT_CURRENCY,
        signed: Boolean = false,
    ): String = buildString {
        if (signed) {
            append(if (amountMinor < 0) '−' else '+')
        } else if (amountMinor < 0) {
            append('−')
        }

        append(formatUnsigned(amountMinor.absoluteValue))

        if (currency != null) {
            append(' ')
            append(currency)
        }
    }

    /**
     * `45000` becomes `450.00` — no grouping, no sign, no currency.
     *
     * Deliberately not [formatUnsigned]: that groups thousands with commas for
     * reading, and a comma is exactly what must not appear in an amount that is
     * about to be typed back into a field or written into a CSV cell.
     */
    fun formatPlain(amountMinor: Long): String {
        val absolute = amountMinor.absoluteValue
        val santim = (absolute % 100).toInt()
        val sign = if (amountMinor < 0) "-" else ""
        return sign + (absolute / 100) + "." + santim.toString().padStart(2, '0')
    }

    /** Digits only — no sign, no currency. Useful for input fields and keypads. */
    fun formatUnsigned(amountMinor: Long): String {
        val absolute = amountMinor.absoluteValue
        val major = absolute / 100
        val santim = absolute % 100
        return buildString {
            append(group(major))
            append(DECIMAL_SEPARATOR)
            if (santim < 10) append('0')
            append(santim)
        }
    }

    private fun group(major: Long): String {
        val digits = major.toString()
        if (digits.length <= 3) return digits
        return buildString(digits.length + digits.length / 3) {
            val leading = digits.length % 3
            if (leading != 0) {
                append(digits, 0, leading)
            }
            var index = leading
            while (index < digits.length) {
                if (isNotEmpty()) append(GROUP_SEPARATOR)
                append(digits, index, index + 3)
                index += 3
            }
        }
    }
}
