package et.alpha.mezgeb.parser.normalize

/**
 * Strips carrier decoration from a sender id so rules can match on something stable.
 *
 * The same sender arrives as `CBE`, `cbe`, `+251CBE`, `CBE-Info` or `SMS from CBE`
 * depending on the network path and the messaging app that posted the notification.
 */
object SenderNormalizer {

    private val DECORATION = Regex("""^(?:sms\s+from\s+|from\s+)""", RegexOption.IGNORE_CASE)
    private val TRAILING_TAG = Regex("""[-_](?:info|alert|notify|sms)$""", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        var s = raw.trim()
        s = DECORATION.replace(s, "")
        s = s.trim().trim('"', '\'', '<', '>')
        // A leading + or country code on an alphanumeric sender id is carrier noise;
        // on a purely numeric shortcode it is meaningful, so only strip when letters
        // are present.
        if (s.any { it.isLetter() }) {
            s = s.removePrefix("+").removePrefix("251")
        }
        s = TRAILING_TAG.replace(s, "")
        return s.trim().uppercase()
    }
}
