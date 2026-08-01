package et.alpha.mezgeb.parser.rule

import et.alpha.mezgeb.parser.normalize.SenderNormalizer

/**
 * The set of senders whose messages are worth parsing at all.
 *
 * This matters more than it looks. Without it every promotional SMS lands in the
 * review queue, the queue becomes noise, and the user learns to ignore it — which
 * destroys the one feature that makes the parser improvable. Allowlist first, parse
 * second.
 *
 * Note it is necessary but not sufficient: financial senders also send fraud warnings,
 * welcome messages and service notifications. Those get past the allowlist and are
 * declined later, by no rule matching them.
 */
class SenderAllowlist(patterns: List<String>) {

    private val regexes = patterns.map { Regex(it, RegexOption.IGNORE_CASE) }

    fun allows(rawSender: String): Boolean {
        val sender = SenderNormalizer.normalize(rawSender)
        return regexes.any { it.matches(sender) }
    }

    companion object {
        /**
         * Seeded from the corpus. Shortcode 127 is telebirr's transactional sender;
         * the alphabetic `telebirr` sender carries marketing only and is deliberately
         * absent.
         */
        val DEFAULT: SenderAllowlist = SenderAllowlist(
            listOf(
                """CBE""",
                """CBE.?BIRR""",
                """EBIRR""",
                """127""",
                """AWASH.*""",
                """DASHEN.*""",
                """ABYSSINIA.*""",
                """BOA""",
                """WEGAGEN.*""",
                """HIBRET.*""",
                """NIB.*""",
                """ZEMEN.*""",
                """OROMIA.*""",
                """COOP.*""",
                """AMHARA.*""",
                """M-?PESA""",
                """AMOLE""",
                """HELLOCASH""",
            ),
        )

        /** Builds an allowlist covering exactly the senders the given rules serve. */
        fun forRules(rules: List<ParserRule>): SenderAllowlist =
            SenderAllowlist(rules.map { it.senderPattern }.distinct())
    }
}
