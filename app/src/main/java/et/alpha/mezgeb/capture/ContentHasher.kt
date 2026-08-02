package et.alpha.mezgeb.capture

import et.alpha.mezgeb.parser.normalize.SenderNormalizer
import java.security.MessageDigest

/**
 * The fallback deduplication key: SHA-256 over the normalised sender and body.
 *
 * Timestamps are deliberately excluded. The same payment seen once as a notification
 * and again through the SMS inbox carries timestamps a few hundred milliseconds
 * apart, and including them would make every message unique — which is the same as
 * having no deduplication at all.
 *
 * ### Deviation from 03-ARCHITECTURE.md, on purpose
 *
 * The architecture says normalisation "strips punctuation". Taken literally that
 * turns `ETB 450.00` and `ETB 45000` into the same string, so two genuinely
 * different payments from one sender would collide and the second would be silently
 * dropped. Dropping a real payment is the worst failure this app has. So punctuation
 * is stripped **except** where it sits between two digits, which keeps decimal points
 * and thousands separators intact while still discarding the trailing full stops,
 * brackets and dashes that differ between the notification and the inbox copy of the
 * same message.
 */
object ContentHasher {

    fun hash(sender: String, body: String): String =
        sha256(SenderNormalizer.normalize(sender) + SEPARATOR + canonicalBody(body))

    /**
     * Lowercased, whitespace collapsed, punctuation dropped unless it is part of a
     * number. Visible for testing — the collision behaviour is the whole point.
     */
    fun canonicalBody(body: String): String {
        val lowered = body.lowercase()
        val out = StringBuilder(lowered.length)
        for (index in lowered.indices) {
            val c = lowered[index]
            when {
                c.isLetterOrDigit() -> out.append(c)
                c.isWhitespace() -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                isBetweenDigits(lowered, index) -> out.append(c)
                // Everything else — punctuation, currency symbols, control characters —
                // is decoration that the two capture paths render differently.
                else -> Unit
            }
        }
        return out.toString().trim()
    }

    private fun isBetweenDigits(text: String, index: Int): Boolean =
        index > 0 &&
            index < text.lastIndex &&
            text[index - 1].isDigit() &&
            text[index + 1].isDigit()

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private const val SEPARATOR = "|"
    private const val HEX = "0123456789abcdef"
}
