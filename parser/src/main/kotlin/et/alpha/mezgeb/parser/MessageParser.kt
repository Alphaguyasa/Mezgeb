package et.alpha.mezgeb.parser

import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.ParsedFields
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.model.UnmatchedReason
import et.alpha.mezgeb.parser.normalize.AmountNormalizer
import et.alpha.mezgeb.parser.normalize.DateNormalizer
import et.alpha.mezgeb.parser.normalize.DirectionResolver
import et.alpha.mezgeb.parser.normalize.PhoneNormalizer
import et.alpha.mezgeb.parser.normalize.SenderNormalizer
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.parser.rule.SenderAllowlist

/**
 * The rule engine.
 *
 * ```
 * sender ─ normalise ─ allowlist ─┬─ not allowed ────────────────► Ignored
 *                                 └─ allowed
 *                                      │
 *                                   select rules by sender, by priority
 *                                      │
 *                                   first body pattern that matches wins
 *                                      │
 *                                   named groups ─ fieldMap ─ normalisers
 *                                      │
 *                                   Success │ Ambiguous │ Unmatched
 * ```
 *
 * The engine never guesses. If two equally-authoritative rules disagree the outcome is
 * [ParseOutcome.Ambiguous]; if a required field will not normalise the outcome is
 * [ParseOutcome.Unmatched]. A wrong number in a ledger is worse than a missing one:
 * the missing one shows up in the review queue where the user fixes it.
 */
class MessageParser(
    rules: List<ParserRule>,
    private val allowlist: SenderAllowlist = SenderAllowlist.DEFAULT,
) {

    /** Highest priority first, so the first match encountered is the most authoritative. */
    private val rules: List<ParserRule> = rules
        .filter { it.isEnabled }
        .sortedWith(compareByDescending<ParserRule> { it.priority }.thenBy { it.id })

    fun parse(sender: String, body: String, receivedAt: Long): ParseOutcome {
        if (!allowlist.allows(sender)) return ParseOutcome.Ignored

        val normalisedSender = SenderNormalizer.normalize(sender)
        val candidates = rules.filter { it.senderRegex.matches(normalisedSender) }
        if (candidates.isEmpty()) {
            return ParseOutcome.Unmatched(UnmatchedReason.NO_RULE_FOR_SENDER)
        }

        // Score every candidate rule, then decide. Deciding as we go made the priority
        // handling hard to follow and easy to get wrong.
        val successes = mutableListOf<Pair<Int, ParseOutcome.Success>>()
        var failureReason: UnmatchedReason? = null

        for (rule in candidates) {
            val match = rule.bodyRegex.find(body) ?: continue
            when (val extracted = extract(rule, match, body, receivedAt)) {
                is Extraction.Ok -> successes += rule.priority to ParseOutcome.Success(
                    fields = extracted.fields,
                    ruleId = rule.id,
                    confidence = rule.confidence,
                )
                is Extraction.Failed -> failureReason = failureReason ?: extracted.reason
            }
        }

        if (successes.isEmpty()) {
            return ParseOutcome.Unmatched(failureReason ?: UnmatchedReason.NO_PATTERN_MATCHED)
        }

        // Only rules of equal, highest priority can conflict. A lower-priority rule
        // matching as well is expected and is not ambiguity.
        val topPriority = successes.maxOf { it.first }
        val winners = successes.filter { it.first == topPriority }.map { it.second }

        return when {
            winners.size == 1 -> winners.first()
            // Equal priority reaching identical conclusions is agreement, not conflict.
            winners.all { it.fields == winners.first().fields } -> winners.first()
            else -> ParseOutcome.Ambiguous(winners)
        }
    }

    /**
     * Where the message's content ends: before any trailing whitespace, ellipsis or
     * full stops. Those are what a truncated preview leaves behind ("ETB 2,0…" or
     * "ETB 2,0..."), and a full stop is also how a sentence ending on a number ends,
     * so a value followed only by them still counts as reaching the end.
     */
    private fun contentEnd(body: String): Int {
        var end = body.length
        while (end > 0 && (body[end - 1].isWhitespace() || body[end - 1] in TRUNCATION_TAIL)) end--
        return end
    }

    private companion object {
        val TRUNCATION_TAIL = setOf('…', '.')
    }

    private sealed interface Extraction {
        data class Ok(val fields: ParsedFields) : Extraction
        data class Failed(val reason: UnmatchedReason) : Extraction
    }

    private fun extract(
        rule: ParserRule,
        match: MatchResult,
        body: String,
        receivedAt: Long,
    ): Extraction {
        val contentEnd = contentEnd(body)

        fun matchGroup(field: SemanticField): MatchGroup? {
            val groupName = rule.fieldMap.entries.firstOrNull { it.value == field }?.key ?: return null
            return runCatching { match.groups[groupName] }.getOrNull()
        }

        /**
         * A captured value that runs into the end of the message may have been cut
         * off there. Notification previews truncate wherever they truncate, and
         * "balance is ETB 2,0…" reads as a perfectly valid 2.0. No real message in the
         * corpus ends on a captured field — they all end on a link or a sign-off — so
         * a value that does is treated as unknown rather than taken at face value.
         */
        fun mayBeCutOff(group: MatchGroup): Boolean = group.range.last + 1 >= contentEnd

        fun group(field: SemanticField): String? {
            val captured = matchGroup(field) ?: return null
            if (mayBeCutOff(captured)) return null
            return captured.value.takeIf { it.isNotBlank() }
        }

        // The amount is the one field a transaction cannot do without, so a cut-off
        // amount sends the message to the review queue instead of being dropped.
        if (matchGroup(SemanticField.AMOUNT)?.let(::mayBeCutOff) == true) {
            return Extraction.Failed(UnmatchedReason.FIELD_EXTRACTION_FAILED)
        }

        val amountMinor = AmountNormalizer.toMinor(group(SemanticField.AMOUNT))
            ?: return Extraction.Failed(UnmatchedReason.NO_AMOUNT)

        val direction = DirectionResolver.resolve(rule, body, group(SemanticField.DIRECTION))
            ?: return Extraction.Failed(UnmatchedReason.FIELD_EXTRACTION_FAILED)

        return Extraction.Ok(
            ParsedFields(
                direction = direction,
                amountMinor = amountMinor,
                channel = rule.channel,
                // Trim punctuation the template leaves attached to a captured name.
                counterpartyName = group(SemanticField.COUNTERPARTY_NAME)
                    ?.trim()
                    ?.trim(',', '.', ':', ';', '-')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                counterpartyPhone = PhoneNormalizer.toE164(group(SemanticField.COUNTERPARTY_PHONE)),
                providerTxnId = group(SemanticField.PROVIDER_TXN_ID)?.trim(),
                balanceAfterMinor = AmountNormalizer.toMinor(group(SemanticField.BALANCE_AFTER)),
                occurredAt = DateNormalizer.toEpochMillis(group(SemanticField.TIMESTAMP), receivedAt),
            ),
        )
    }
}
