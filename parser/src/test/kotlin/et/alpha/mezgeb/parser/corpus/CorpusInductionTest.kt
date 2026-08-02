package et.alpha.mezgeb.parser.corpus

import et.alpha.mezgeb.parser.induce.InductionCandidate
import et.alpha.mezgeb.parser.induce.InductionOutcome
import et.alpha.mezgeb.parser.induce.RuleInducer
import et.alpha.mezgeb.parser.induce.TaggedSpan
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.ParserRule
import et.alpha.mezgeb.parser.rule.RuleSetLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The claim rule induction makes, tested against real messages.
 *
 * A bundled rule is taken away, and a replacement is induced from the spans a user
 * would have drawn on **one** of the messages that rule used to read. The replacement
 * then has to read the rest of them.
 *
 * The spans are not hand-written here: they are taken from where the removed rule's
 * own capture groups landed, which is exactly the text a user would have selected. So
 * this measures the induction algorithm rather than the test author's ability to
 * choose helpful offsets.
 */
class CorpusInductionTest {

    private val inducer = RuleInducer()

    private val samples: List<CorpusMessage> by lazy {
        CorpusLoader.load()
            .filter { it.split != CorpusSplit.NOISE }
            .flatMap { loaded ->
                loaded.file.samples
                    .filter { it.expected != null }
                    .map { CorpusMessage(it.id, loaded.file.sender, it.body, it.expected!!) }
            }
    }

    private val bundledRules: List<ParserRule> by lazy {
        RuleSetLoader.fromResource("parser_rules.json").rules
    }

    @Test
    @DisplayName("a rule taught from one message reads the rest of that template")
    fun `induction reproduces a bundled rule from one example`() {
        assumeTrue(samples.isNotEmpty(), "Corpus not collected yet")

        // The rule with the most real messages behind it, chosen at run time so the
        // test does not rot when rule ids change.
        val (removed, covered) = bundledRules
            .map { rule -> rule to samples.filter { rule.matches(it) } }
            .maxByOrNull { it.second.size }
            ?: fail("no bundled rules to test against")

        assumeTrue(covered.size >= MIN_TEMPLATE_SAMPLES, "no template has enough samples yet")

        val teachingExample = covered.first()
        val rest = covered.drop(1)

        val spans = removed.spansOn(teachingExample.body)
            ?: fail("could not derive spans from ${removed.id} on ${teachingExample.id}")

        val outcome = inducer.induce(
            sender = teachingExample.sender,
            body = teachingExample.body,
            spans = spans,
            channel = removed.channel,
            direction = removed.fixedDirection() ?: fail("${removed.id} is not a fixed-direction rule"),
            ruleId = "induced_from_${teachingExample.id}",
        )

        val induced = when (outcome) {
            is InductionOutcome.Failed -> fail("induction failed: ${outcome.reason}")
            is InductionOutcome.Success -> outcome.induced
        }

        val report = inducer.evaluate(
            rule = induced.rule,
            candidates = rest.map {
                InductionCandidate(it.id, it.sender, it.body, RECEIVED_AT)
            },
        )

        println(
            """
            |RULE INDUCTION — reproduced from one message
            |  removed rule    : ${removed.id}
            |  taught from     : ${teachingExample.id}
            |  other messages  : ${rest.size}
            |  matched         : ${report.matched}
            |  hit rate        : ${"%.1f".format(report.hitRate * 100)}%
            |  degraded field  : ${induced.degradedField ?: "none"}
            """.trimMargin(),
        )

        assertTrue(
            report.hitRate >= MIN_HIT_RATE,
            "a rule taught from one message read only ${report.matched} of ${rest.size} " +
                "others (${"%.1f".format(report.hitRate * 100)}%). Below ${MIN_HIT_RATE * 100}% " +
                "the teach-a-rule flow is not worth offering: the user would have to teach " +
                "the same template again and again.",
        )

        // Matching is not enough — it has to extract the right number.
        val expectedById = rest.associateBy { it.id }
        report.samples.forEach { sample ->
            val expected = expectedById.getValue(sample.candidateId).expected
            assertEquals(
                expected.amountMinor,
                sample.fields.amountMinor,
                "induced rule read the wrong amount from ${sample.candidateId}",
            )
        }
    }

    @Test
    @DisplayName("a rule taught from one message does not read every other message")
    fun `induction does not produce a rule that matches everything`() {
        assumeTrue(samples.isNotEmpty(), "Corpus not collected yet")

        val (rule, covered) = bundledRules
            .map { r -> r to samples.filter { r.matches(it) } }
            .maxByOrNull { it.second.size }
            ?: fail("no bundled rules to test against")
        assumeTrue(covered.size >= MIN_TEMPLATE_SAMPLES, "no template has enough samples yet")

        val example = covered.first()
        val spans = example.let { rule.spansOn(it.body) } ?: fail("no spans")

        val induced = inducer.induce(
            sender = example.sender,
            body = example.body,
            spans = spans,
            channel = rule.channel,
            direction = rule.fixedDirection() ?: fail("not a fixed-direction rule"),
        ).let { (it as InductionOutcome.Success).induced }

        // Messages from other senders entirely. An induced rule that matched these
        // would be writing numbers into the ledger from templates nobody taught it.
        val strangers = samples.filter { it.sender != example.sender }
        assumeTrue(strangers.isNotEmpty(), "only one sender in the corpus")

        val report = inducer.evaluate(
            rule = induced.rule,
            candidates = strangers.map { InductionCandidate(it.id, it.sender, it.body, RECEIVED_AT) },
        )

        assertEquals(
            0,
            report.matched,
            "an induced rule matched ${report.matched} messages from other senders",
        )
    }

    // --- Helpers -----------------------------------------------------------------------

    private data class CorpusMessage(
        val id: String,
        val sender: String,
        val body: String,
        val expected: ExpectedFields,
    )

    private fun ParserRule.matches(message: CorpusMessage): Boolean =
        senderRegex.matches(normalisedSender(message.sender)) && bodyRegex.containsMatchIn(message.body)

    private fun normalisedSender(sender: String): String =
        et.alpha.mezgeb.parser.normalize.SenderNormalizer.normalize(sender)

    /**
     * Where this rule's capture groups land in [body], as the spans a user would have
     * drawn by selecting the same text.
     */
    private fun ParserRule.spansOn(body: String): List<TaggedSpan>? {
        val match = bodyRegex.find(body) ?: return null
        return fieldMap
            .mapNotNull { (groupName, field) ->
                val range = runCatching { match.groups[groupName]?.range }.getOrNull()
                    ?: return@mapNotNull null
                if (range.isEmpty()) return@mapNotNull null
                TaggedSpan(field, range.first, range.last + 1)
            }
            // A rule may capture nested or adjacent groups; a user's selection cannot
            // overlap, so neither can the derived spans.
            .sortedBy { it.start }
            .fold(mutableListOf<TaggedSpan>()) { kept, span ->
                if (kept.isEmpty() || span.start >= kept.last().end) kept += span
                kept
            }
            .takeIf { spans -> spans.any { it.field == SemanticField.AMOUNT } }
    }

    private companion object {
        const val RECEIVED_AT = 1_785_000_000_000L
        const val MIN_TEMPLATE_SAMPLES = 3

        /**
         * The architecture's own "this rule looks too specific" threshold, used here as
         * a floor on the algorithm rather than as a warning to a user.
         */
        const val MIN_HIT_RATE = 0.6f
    }
}
