package et.alpha.mezgeb.parser.corpus

import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.ParsedFields
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * Every bundled rule, fed every truncation of every train sample.
 *
 * Notification text is routinely cut short — by the messaging app's preview, by the
 * system's notification size limit, by a long sender name — and the cut lands
 * wherever it lands, including in the middle of an amount. "You have received
 * ETB 2,0…" must not become a 2-birr payment: a wrong number in a ledger is believed,
 * where a missing one is noticed in the review queue.
 *
 * So the rule here is the same one the accuracy harness applies, made exhaustive: a
 * truncated message may parse, or may not, but if it parses then every field it
 * reports must be the value the full message reports. An optional field may go
 * missing; it may never change. The timestamp may also fall back to the time the
 * message arrived, which is what a message with no timestamp at all gets.
 *
 * Train split only, so the holdout stays untouched (see [CorpusHoldoutTest]).
 */
class TruncationSafetyTest {

    private val train: List<LoadedCorpusFile> by lazy {
        CorpusLoader.load().filter { it.split == CorpusSplit.TRAIN }
    }

    @Test
    @DisplayName("no truncation of a train sample parses to a different value")
    fun `truncated messages never parse to a wrong value`() {
        assumeTrue(
            train.any { it.file.samples.isNotEmpty() },
            "Corpus not collected yet — see docs/04-SMS-SAMPLE-COLLECTION.md",
        )
        val parser = CorpusAccuracy.parser()
        val violations = mutableListOf<String>()
        var checked = 0

        for (corpus in train) {
            for (sample in corpus.file.samples) {
                if (sample.expected == null) continue
                val full = parser.parse(corpus.file.sender, sample.body, CorpusAccuracy.RECEIVED_AT)
                // A sample the parser cannot read in full is the accuracy test's business.
                if (full !is ParseOutcome.Success) continue

                for (length in 1 until sample.body.length) {
                    val prefix = sample.body.substring(0, length)
                    for (body in listOf(prefix, "$prefix…", "$prefix...")) {
                        checked++
                        val outcome = parser.parse(corpus.file.sender, body, CorpusAccuracy.RECEIVED_AT)
                        violationsIn(full.fields, outcome)?.let { problem ->
                            violations += "${sample.id} cut at $length (${body.takeLast(24).quoted()}): $problem"
                        }
                    }
                }
            }
        }

        println("Truncation safety: $checked truncated variants checked, ${violations.size} violations")
        if (violations.isNotEmpty()) {
            fail(
                "${violations.size} truncated message(s) parsed to a value the full message " +
                    "does not contain:\n" + violations.take(MAX_REPORTED).joinToString("\n"),
            )
        }
    }

    private fun violationsIn(full: ParsedFields, outcome: ParseOutcome): String? {
        val truncated = when (outcome) {
            is ParseOutcome.Success -> outcome.fields
            // Two rules each reading the cut message differently is still a guess the
            // user would have to arbitrate, but the engine already refuses to pick, so
            // only the candidates' values matter and they are checked the same way.
            is ParseOutcome.Ambiguous -> return outcome.candidates
                .firstNotNullOfOrNull { violationsIn(full, it) }
            else -> return null
        }

        val problems = buildList {
            if (truncated.direction != full.direction) add("direction ${truncated.direction}")
            if (truncated.amountMinor != full.amountMinor) add("amount ${truncated.amountMinor}")
            if (truncated.channel != full.channel) add("channel ${truncated.channel}")
            optional("name", truncated.counterpartyName, full.counterpartyName)
            optional("phone", truncated.counterpartyPhone, full.counterpartyPhone)
            optional("reference", truncated.providerTxnId, full.providerTxnId)
            optional("balance", truncated.balanceAfterMinor, full.balanceAfterMinor)
            if (truncated.occurredAt != full.occurredAt &&
                truncated.occurredAt != CorpusAccuracy.RECEIVED_AT
            ) {
                add("occurredAt ${truncated.occurredAt}")
            }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun MutableList<String>.optional(label: String, truncated: Any?, full: Any?) {
        if (truncated != null && truncated != full) add("$label ${truncated.toString().quoted()} (full: $full)")
    }

    private fun String.quoted() = "\"$this\""

    private companion object {
        const val MAX_REPORTED = 60
    }
}
