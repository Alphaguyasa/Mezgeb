package et.alpha.mezgeb.parser.corpus

import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.normalize.AmountNormalizer
import et.alpha.mezgeb.parser.normalize.DateNormalizer
import et.alpha.mezgeb.parser.normalize.PhoneNormalizer
import et.alpha.mezgeb.parser.rule.RuleSetLoader
import et.alpha.mezgeb.parser.rule.SenderAllowlist

/**
 * Scores the parser against hand-labelled corpus samples.
 *
 * The asymmetry here is the whole point. A **miss** — Unmatched — is acceptable: the
 * message lands in the review queue and the user teaches a rule. A **wrong answer** is
 * not: it silently writes a wrong number into someone's ledger, and nothing surfaces
 * it. So false positives and value mismatches are counted separately from misses and
 * are fatal to the build.
 */
object CorpusAccuracy {

    /** Fixed so a fallback-to-receivedAt is detectable rather than accidentally right. */
    const val RECEIVED_AT: Long = 1_785_000_000_000L

    data class FieldScore(
        var expectedPresent: Int = 0,
        var predictedPresent: Int = 0,
        var correct: Int = 0,
        var wrong: Int = 0,
        var missed: Int = 0,
    ) {
        /** Of what the parser asserted, how much was right. */
        val precision: Double get() = if (predictedPresent == 0) 1.0 else correct.toDouble() / predictedPresent
        /** Of what was there to find, how much it found correctly. */
        val recall: Double get() = if (expectedPresent == 0) 1.0 else correct.toDouble() / expectedPresent
    }

    data class Mismatch(
        val sampleId: String,
        val field: String,
        val expected: String?,
        val actual: String?,
        val fatal: Boolean,
    )

    data class SenderScore(
        val sender: String,
        var total: Int = 0,
        var exact: Int = 0,
        var missed: Int = 0,
        var wrong: Int = 0,
    ) {
        val accuracy: Double get() = if (total == 0) 1.0 else exact.toDouble() / total
    }

    data class Report(
        val perField: Map<String, FieldScore>,
        val perSender: Map<String, SenderScore>,
        val mismatches: List<Mismatch>,
        val falsePositives: List<Mismatch>,
        val totalSamples: Int,
        val exactSamples: Int,
        val missedSamples: Int,
    ) {
        val overallAccuracy: Double get() = if (totalSamples == 0) 1.0 else exactSamples.toDouble() / totalSamples
        val isClean: Boolean get() = mismatches.none { it.fatal } && falsePositives.isEmpty()
    }

    fun parser(): MessageParser {
        val ruleSet = RuleSetLoader.fromResource("parser_rules.json")
        return MessageParser(ruleSet.rules, SenderAllowlist.DEFAULT)
    }

    fun evaluate(files: List<LoadedCorpusFile>): Report {
        val parser = parser()
        val perField = linkedMapOf<String, FieldScore>()
        val perSender = linkedMapOf<String, SenderScore>()
        val mismatches = mutableListOf<Mismatch>()
        val falsePositives = mutableListOf<Mismatch>()
        var total = 0
        var exact = 0
        var missed = 0

        fun field(name: String) = perField.getOrPut(name) { FieldScore() }

        for (loaded in files) {
            for (sample in loaded.file.samples) {
                total++
                val senderKey = if (loaded.split == CorpusSplit.NOISE) "noise" else loaded.file.sender
                val senderScore = perSender.getOrPut(senderKey) { SenderScore(senderKey) }
                senderScore.total++

                val outcome = parser.parse(loaded.file.sender, sample.body, RECEIVED_AT)

                if (sample.expected == null) {
                    // Noise. Anything other than Ignored or Unmatched is a false positive.
                    when (outcome) {
                        is ParseOutcome.Success, is ParseOutcome.Ambiguous -> {
                            senderScore.wrong++
                            falsePositives += Mismatch(
                                sampleId = sample.id,
                                field = "outcome",
                                expected = "Ignored or Unmatched",
                                actual = outcome::class.simpleName,
                                fatal = true,
                            )
                        }
                        else -> {
                            // Correctly declining a noise message is a correct outcome
                            // and counts toward accuracy, not just against it.
                            senderScore.exact++
                            exact++
                        }
                    }
                    continue
                }

                val success = outcome as? ParseOutcome.Success
                if (success == null) {
                    missed++
                    senderScore.missed++
                    // A miss still costs recall on every field the sample carried.
                    expectedFieldsOf(sample).forEach { (name, _) ->
                        field(name).expectedPresent++
                        field(name).missed++
                    }
                    if (outcome is ParseOutcome.Ambiguous) {
                        mismatches += Mismatch(sample.id, "outcome", "Success", "Ambiguous", fatal = true)
                    }
                    continue
                }

                var sampleClean = true
                for ((name, expectedValue) in expectedFieldsOf(sample)) {
                    val score = field(name)
                    score.expectedPresent++
                    val actualValue = actualFieldOf(success, name)
                    if (actualValue != null) score.predictedPresent++
                    when {
                        actualValue == null -> {
                            score.missed++
                            sampleClean = false
                            mismatches += Mismatch(sample.id, name, expectedValue, null, fatal = false)
                        }
                        actualValue == expectedValue -> score.correct++
                        else -> {
                            score.wrong++
                            sampleClean = false
                            mismatches += Mismatch(sample.id, name, expectedValue, actualValue, fatal = true)
                        }
                    }
                }

                // Fields the parser asserted that the sample says are absent. Inventing
                // a value is a wrong answer, not a bonus.
                for (name in ALL_FIELDS) {
                    if (expectedFieldsOf(sample).containsKey(name)) continue
                    val actualValue = actualFieldOf(success, name) ?: continue
                    if (name == "occurredAt") continue // fallback to receivedAt is by design
                    field(name).predictedPresent++
                    field(name).wrong++
                    sampleClean = false
                    mismatches += Mismatch(sample.id, name, null, actualValue, fatal = true)
                }

                if (sampleClean) {
                    exact++
                    senderScore.exact++
                } else {
                    senderScore.wrong++
                }
            }
        }

        return Report(perField, perSender, mismatches, falsePositives, total, exact, missed)
    }

    private val ALL_FIELDS = listOf(
        "direction", "amountMinor", "counterpartyName", "counterpartyPhone",
        "providerTxnId", "balanceAfterMinor", "occurredAt",
    )

    /** The fields a sample actually claims, normalised to comparable strings. */
    private fun expectedFieldsOf(sample: CorpusSample): Map<String, String> {
        val e = sample.expected ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        e.direction?.let { out["direction"] = it }
        e.amountMinor?.let { out["amountMinor"] = it.toString() }
        e.counterpartyName?.let { out["counterpartyName"] = it }
        e.counterpartyPhone?.let { out["counterpartyPhone"] = it }
        e.providerTxnId?.let { out["providerTxnId"] = it }
        e.balanceAfterMinor?.let { out["balanceAfterMinor"] = it.toString() }
        e.occurredAt?.let { out["occurredAt"] = DateNormalizer.parse(it)?.toString() ?: it }
        return out
    }

    private fun actualFieldOf(success: ParseOutcome.Success, name: String): String? {
        val f = success.fields
        return when (name) {
            "direction" -> f.direction.name
            "amountMinor" -> f.amountMinor.toString()
            "counterpartyName" -> f.counterpartyName
            "counterpartyPhone" -> f.counterpartyPhone
            "providerTxnId" -> f.providerTxnId
            "balanceAfterMinor" -> f.balanceAfterMinor?.toString()
            "occurredAt" -> f.occurredAt.toString()
            else -> null
        }
    }

    /** Unused today, kept so the harness and the normalizers stay in one import graph. */
    @Suppress("unused")
    private fun sanity() {
        AmountNormalizer.toMinor("1")
        PhoneNormalizer.toE164("0912345678")
        SemanticField.AMOUNT.name
    }

    fun format(report: Report, title: String): String = buildString {
        appendLine()
        appendLine("=".repeat(72))
        appendLine(title)
        appendLine("=".repeat(72))
        appendLine()
        appendLine("%-26s %7s %7s %7s %7s".format("SENDER", "SAMPLES", "EXACT", "MISSED", "WRONG"))
        appendLine("-".repeat(72))
        report.perSender.values.forEach { s ->
            appendLine(
                "%-26s %7d %7d %7d %7d   %5.1f%%".format(
                    s.sender.take(26), s.total, s.exact, s.missed, s.wrong, s.accuracy * 100,
                ),
            )
        }
        appendLine("-".repeat(72))
        appendLine()
        appendLine("%-22s %9s %9s %9s %9s".format("FIELD", "EXPECTED", "CORRECT", "PRECISION", "RECALL"))
        appendLine("-".repeat(72))
        report.perField.forEach { (name, s) ->
            appendLine(
                "%-22s %9d %9d %8.1f%% %8.1f%%".format(
                    name, s.expectedPresent, s.correct, s.precision * 100, s.recall * 100,
                ),
            )
        }
        appendLine("-".repeat(72))
        appendLine()
        appendLine("samples          : ${report.totalSamples}")
        appendLine("exact            : ${report.exactSamples}")
        appendLine("missed           : ${report.missedSamples}   (acceptable — lands in the review queue)")
        appendLine("false positives  : ${report.falsePositives.size}   (fatal)")
        appendLine("wrong values     : ${report.mismatches.count { it.fatal }}   (fatal)")
        appendLine("OVERALL ACCURACY : %.2f%%".format(report.overallAccuracy * 100))
        appendLine("=".repeat(72))

        val fatal = report.mismatches.filter { it.fatal } + report.falsePositives
        if (fatal.isNotEmpty()) {
            appendLine()
            appendLine("FATAL — wrong answers (a miss is fine, a wrong value is not):")
            fatal.take(30).forEach {
                appendLine("  ${it.sampleId} [${it.field}] expected=${it.expected} actual=${it.actual}")
            }
            if (fatal.size > 30) appendLine("  … and ${fatal.size - 30} more")
        }
        val soft = report.mismatches.filter { !it.fatal }
        if (soft.isNotEmpty()) {
            appendLine()
            appendLine("Missed fields (not fatal):")
            soft.take(20).forEach {
                appendLine("  ${it.sampleId} [${it.field}] expected=${it.expected}")
            }
            if (soft.size > 20) appendLine("  … and ${soft.size - 20} more")
        }
    }
}
