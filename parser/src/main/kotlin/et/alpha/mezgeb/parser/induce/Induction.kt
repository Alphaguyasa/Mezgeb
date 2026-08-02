package et.alpha.mezgeb.parser.induce

import et.alpha.mezgeb.parser.model.ParsedFields
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.ParserRule

/**
 * A stretch of the message the user has pointed at and named.
 *
 * [start] is inclusive, [end] exclusive, in character offsets into the body — the
 * same convention a text selection uses, so the UI can hand its selection straight
 * over without arithmetic.
 */
data class TaggedSpan(
    val field: SemanticField,
    val start: Int,
    val end: Int,
) {
    val length: Int get() = end - start
}

/** What a span's text looks like, which decides how loosely it is matched. */
enum class SpanClass {
    /** `1,200.50` — digits, separators, optional santim. */
    CURRENCY,

    /** `+251911234567`, `0911 234 567`. */
    PHONE,

    /** `CJ4T5X9AB1` — a provider reference. */
    ALPHANUMERIC_ID,

    /** Anything else. Matched non-greedily, because names are unpredictable. */
    FREE_TEXT,
}

/**
 * A rule generated from tagged spans, plus what had to be given up to make it work.
 *
 * @param degradedField set when a span's pattern had to be loosened for the rule to
 *   compile at all. The UI says so rather than silently shipping a vaguer rule than
 *   the user drew.
 */
data class InducedRule(
    val rule: ParserRule,
    val spanClasses: Map<SemanticField, SpanClass>,
    val degradedField: SemanticField? = null,
)

/** Why induction could not produce a rule. All of these are user-correctable. */
enum class InductionFailure {
    NO_SPANS,

    /** Without an amount there is no transaction to record. */
    NO_AMOUNT_SPAN,

    OVERLAPPING_SPANS,
    SPAN_OUT_OF_BOUNDS,
    EMPTY_SPAN,

    /** The same field tagged twice — the engine would not know which to believe. */
    DUPLICATE_FIELD,

    /** Generated, degraded, and still would not compile. Should be unreachable. */
    PATTERN_WILL_NOT_COMPILE,
}

sealed interface InductionOutcome {
    data class Success(val induced: InducedRule) : InductionOutcome
    data class Failed(val reason: InductionFailure) : InductionOutcome
}

/** A message the candidate rule can be tried against. */
data class InductionCandidate(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
)

/** One message the rule matched, and what it pulled out of it. */
data class SampleExtraction(
    val candidateId: String,
    val body: String,
    val fields: ParsedFields,
)

/**
 * How a candidate rule did against the messages still waiting from the same sender.
 *
 * This is what the user sees before saving: "matches 14 of 16" is a claim they can
 * check, and the three sample extractions are how they check it.
 */
data class InductionReport(
    val matched: Int,
    val total: Int,
    val samples: List<SampleExtraction>,
    /** Rules already enabled that also match these messages. */
    val conflicts: List<ParserRule> = emptyList(),
) {
    val hitRate: Float get() = if (total == 0) 0f else matched.toFloat() / total

    /**
     * A rule that reads one message in three is a rule learned from a coincidence —
     * usually a date or an amount left inside a literal. Below this, the user is
     * warned rather than stopped: they may genuinely be teaching a rare template.
     */
    val looksTooSpecific: Boolean
        get() = total >= MIN_SAMPLES_TO_JUDGE && hitRate < MIN_ACCEPTABLE_HIT_RATE

    companion object {
        const val MIN_SAMPLES_TO_JUDGE = 5
        const val MIN_ACCEPTABLE_HIT_RATE = 0.6f
    }
}
