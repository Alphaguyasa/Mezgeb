package et.alpha.mezgeb.parser.induce

import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.parser.model.Direction
import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.normalize.SenderNormalizer
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.parser.rule.SenderAllowlist

/**
 * Turns "this bit is the amount, that bit is who paid" into a parser rule.
 *
 * This is the feature that makes the app improvable by the person using it. A shop
 * whose bank sends a template nobody anticipated is otherwise stuck; here they select
 * the amount, tap Amount, and the app reads that template from then on — and, because
 * the rule is data, so does every message already waiting in the queue.
 *
 * ```
 * body + tagged spans
 *      │
 *   validate spans ── overlapping / empty / no amount ──► Failed
 *      │
 *   split into literal and span segments
 *      │
 *   literals: escape, then generalise digit runs to \d+ and whitespace to \s+
 *   spans:    named group, pattern inferred from what the text looks like
 *      │
 *   anchor, compile ── fails ──► degrade the most specific span, retry once
 *      │
 *   ParserRule
 * ```
 *
 * The generalisation step is the one that is easy to skip and fatal to skip. A rule
 * learned from a message containing "on 14/03" would otherwise only ever match
 * messages sent on the fourteenth of March.
 */
class RuleInducer {

    fun induce(
        sender: String,
        body: String,
        spans: List<TaggedSpan>,
        channel: Channel = Channel.OTHER,
        direction: Direction = Direction.IN,
        ruleId: String = DEFAULT_RULE_ID,
        ruleName: String = DEFAULT_RULE_NAME,
    ): InductionOutcome {
        val sorted = spans.sortedBy { it.start }
        validate(sorted, body)?.let { return InductionOutcome.Failed(it) }

        val classes = sorted.associate {
            it.field to classify(it.field, body.substring(it.start, it.end))
        }

        // First attempt: every span as specific as its content allows.
        build(sender, body, sorted, classes, channel, direction, ruleId, ruleName)
            ?.let { return InductionOutcome.Success(InducedRule(it, classes)) }

        // Retry once with the most specific span loosened. In practice this is
        // unreachable — the generated patterns are well-formed by construction — but a
        // rule builder that can throw at the user is worse than one that degrades.
        val toDegrade = sorted
            .filter { classes[it.field] != SpanClass.FREE_TEXT }
            .minByOrNull { SPECIFICITY.indexOf(classes[it.field]) }
            ?: return InductionOutcome.Failed(InductionFailure.PATTERN_WILL_NOT_COMPILE)

        val degraded = classes + (toDegrade.field to SpanClass.FREE_TEXT)
        val rule = build(sender, body, sorted, degraded, channel, direction, ruleId, ruleName)
            ?: return InductionOutcome.Failed(InductionFailure.PATTERN_WILL_NOT_COMPILE)

        return InductionOutcome.Success(
            InducedRule(rule = rule, spanClasses = degraded, degradedField = toDegrade.field),
        )
    }

    /**
     * Runs a candidate rule over the messages still waiting from the same sender.
     *
     * The counts are the point. A rule the user cannot check before saving is a rule
     * they have to trust, and this app's whole argument is that they should not have to.
     */
    fun evaluate(
        rule: ParserRule,
        candidates: List<InductionCandidate>,
        existingRules: List<ParserRule> = emptyList(),
        sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
    ): InductionReport {
        val parser = parserFor(rule)
        val samples = mutableListOf<SampleExtraction>()
        var matched = 0

        for (candidate in candidates) {
            val outcome = parser.parse(candidate.sender, candidate.body, candidate.receivedAt)
            if (outcome !is ParseOutcome.Success) continue
            matched++
            if (samples.size < sampleLimit) {
                samples += SampleExtraction(candidate.id, candidate.body, outcome.fields)
            }
        }

        return InductionReport(
            matched = matched,
            total = candidates.size,
            samples = samples,
            conflicts = findConflicts(existingRules, candidates),
        )
    }

    /**
     * Enabled rules that already read these messages.
     *
     * Saving a second rule for a template that is already handled is how a review
     * queue quietly grows two rules that disagree, which the engine then reports as
     * ambiguous and refuses to parse at all.
     */
    fun findConflicts(
        existingRules: List<ParserRule>,
        candidates: List<InductionCandidate>,
    ): List<ParserRule> = existingRules
        .filter { it.isEnabled }
        .filter { existing ->
            val parser = parserFor(existing)
            candidates.any { candidate ->
                parser.parse(candidate.sender, candidate.body, candidate.receivedAt) is
                    ParseOutcome.Success
            }
        }

    // --- Validation ----------------------------------------------------------------

    private fun validate(spans: List<TaggedSpan>, body: String): InductionFailure? {
        if (spans.isEmpty()) return InductionFailure.NO_SPANS
        if (spans.none { it.field == SemanticField.AMOUNT }) {
            return InductionFailure.NO_AMOUNT_SPAN
        }
        if (spans.distinctBy { it.field }.size != spans.size) {
            return InductionFailure.DUPLICATE_FIELD
        }
        spans.forEach { span ->
            if (span.start < 0 || span.end > body.length) {
                return InductionFailure.SPAN_OUT_OF_BOUNDS
            }
            if (span.length <= 0) return InductionFailure.EMPTY_SPAN
        }
        spans.zipWithNext().forEach { (first, second) ->
            if (second.start < first.end) return InductionFailure.OVERLAPPING_SPANS
        }
        return null
    }

    // --- Pattern construction --------------------------------------------------------

    private fun build(
        sender: String,
        body: String,
        spans: List<TaggedSpan>,
        classes: Map<SemanticField, SpanClass>,
        channel: Channel,
        direction: Direction,
        ruleId: String,
        ruleName: String,
    ): ParserRule? {
        val pattern = buildString {
            var cursor = 0
            spans.forEach { span ->
                append(generaliseLiteral(body.substring(cursor, span.start)))
                val group = groupNameFor(span.field)
                append("(?<").append(group).append('>')
                append(patternFor(classes.getValue(span.field)))
                append(')')
                cursor = span.end
            }
            // Only a little of what follows the last span, and no end anchor. See
            // TRAILING_CONTEXT_CHARS.
            append(generaliseLiteral(body.substring(cursor).take(TRAILING_CONTEXT_CHARS)))
        }

        val senderPattern = Regex.escape(SenderNormalizer.normalize(sender))
        val rule = ParserRule(
            id = ruleId,
            name = ruleName,
            senderPattern = senderPattern,
            channel = channel,
            bodyPattern = pattern,
            fieldMap = spans.associate { groupNameFor(it.field) to it.field },
            directionStrategy = when (direction) {
                Direction.IN -> DirectionStrategy.FIXED_IN
                Direction.OUT -> DirectionStrategy.FIXED_OUT
            },
            isEnabled = true,
            // Above the bundled rules: a rule the shop taught itself describes its own
            // provider better than anything shipped in the APK.
            priority = USER_RULE_PRIORITY,
            // Hand-tagged from one example. Honest about being less trustworthy than a
            // rule that was written against a whole corpus.
            confidence = INDUCED_CONFIDENCE,
        )

        // Compile now rather than lazily, so a bad pattern is a return value here
        // instead of an exception thrown at capture time.
        return runCatching {
            Regex(rule.senderPattern)
            Regex(rule.bodyPattern)
            rule
        }.getOrNull()
    }

    /**
     * Escapes a literal segment, then loosens the parts of it that vary between
     * messages: any run of digits becomes `\d+`, any run of whitespace `\s+`.
     */
    internal fun generaliseLiteral(literal: String): String {
        if (literal.isEmpty()) return ""
        val out = StringBuilder()
        var index = 0
        while (index < literal.length) {
            val c = literal[index]
            when {
                c.isDigit() -> {
                    while (index < literal.length && literal[index].isDigit()) index++
                    out.append("""\d+""")
                }

                c.isWhitespace() -> {
                    while (index < literal.length && literal[index].isWhitespace()) index++
                    out.append("""\s+""")
                }

                else -> {
                    val start = index
                    while (index < literal.length &&
                        !literal[index].isDigit() &&
                        !literal[index].isWhitespace()
                    ) {
                        index++
                    }
                    out.append(Regex.escape(literal.substring(start, index)))
                }
            }
        }
        return out.toString()
    }

    /**
     * How tightly a span should be matched.
     *
     * The field the user tagged comes first, and the text only confirms it. Content
     * alone is ambiguous in exactly the cases that matter: a ten-digit phone number
     * with no plus is indistinguishable from an amount, and picking wrong there
     * produces a rule that reads a phone number as money. The user already told us
     * which it was by tapping a chip.
     */
    internal fun classify(field: SemanticField, text: String): SpanClass {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SpanClass.FREE_TEXT
        return when (field) {
            SemanticField.AMOUNT,
            SemanticField.BALANCE_AFTER,
            -> if (CURRENCY_LIKE.matches(trimmed)) SpanClass.CURRENCY else SpanClass.FREE_TEXT

            SemanticField.COUNTERPARTY_PHONE ->
                if (PHONE_LIKE.matches(trimmed)) SpanClass.PHONE else SpanClass.FREE_TEXT

            SemanticField.PROVIDER_TXN_ID ->
                if (ALPHANUMERIC_ID_LIKE.matches(trimmed)) {
                    SpanClass.ALPHANUMERIC_ID
                } else {
                    SpanClass.FREE_TEXT
                }

            // A name, a direction word, a timestamp: no shape worth assuming.
            SemanticField.COUNTERPARTY_NAME,
            SemanticField.DIRECTION,
            SemanticField.TIMESTAMP,
            -> SpanClass.FREE_TEXT
        }
    }

    private fun patternFor(spanClass: SpanClass): String = when (spanClass) {
        SpanClass.CURRENCY -> """[\d,\s]+(?:\.\d{1,2})?"""
        SpanClass.PHONE -> """\+?\d[\d\s-]{7,}"""
        SpanClass.ALPHANUMERIC_ID -> """[A-Za-z0-9]{6,}"""
        SpanClass.FREE_TEXT -> """.+?"""
    }

    private fun parserFor(rule: ParserRule) = MessageParser(
        rules = listOf(rule),
        // The allowlist exists to keep promotional traffic out of the review queue.
        // These messages are already in it, so it has nothing left to decide.
        allowlist = SenderAllowlist(listOf(rule.senderPattern)),
    )

    private fun groupNameFor(field: SemanticField): String = when (field) {
        SemanticField.AMOUNT -> "amount"
        SemanticField.DIRECTION -> "direction"
        SemanticField.COUNTERPARTY_NAME -> "name"
        SemanticField.COUNTERPARTY_PHONE -> "phone"
        SemanticField.PROVIDER_TXN_ID -> "ref"
        SemanticField.BALANCE_AFTER -> "balance"
        SemanticField.TIMESTAMP -> "time"
    }

    private companion object {
        const val DEFAULT_RULE_ID = "induced"
        const val DEFAULT_RULE_NAME = "Taught rule"
        const val DEFAULT_SAMPLE_LIMIT = 3
        const val USER_RULE_PRIORITY = 100
        const val INDUCED_CONFIDENCE = 0.8f

        /**
         * How much of the text after the last tagged span is kept.
         *
         * **Deviation from 03-ARCHITECTURE.md section 5, forced by real messages.** The
         * documented algorithm anchors the pattern with `^...$`, which requires the
         * whole body to match. CBE ends every message with a receipt URL containing a
         * random token — `https://mbreciept.cbe.com.et/i0-<random token>` — and
         * EBIRR does the same. Anchored, a rule taught from one of those messages
         * matched **0 of 21** others in the corpus: the algorithm as written does not
         * survive contact with the providers it was written for.
         *
         * So the tail is truncated and neither end is anchored. Enough of the closing
         * text is kept to bound the last capture group, which is what a non-greedy
         * group needs to stop in the right place; the rest — receipt links, marketing
         * footers, feedback forms — is left alone. The literals *between* spans are
         * kept in full, because those are the template's actual signature.
         */
        const val TRAILING_CONTEXT_CHARS = 24

        /** Most specific first. The first of these present is the one degraded. */
        val SPECIFICITY = listOf(
            SpanClass.ALPHANUMERIC_ID,
            SpanClass.PHONE,
            SpanClass.CURRENCY,
            SpanClass.FREE_TEXT,
        )

        val CURRENCY_LIKE = Regex("""\d[\d,\s]*(?:\.\d{1,2})?""")
        val PHONE_LIKE = Regex("""\+?\d[\d\s-]{7,}""")
        val ALPHANUMERIC_ID_LIKE = Regex("""[A-Za-z0-9]{6,}""")
    }
}
