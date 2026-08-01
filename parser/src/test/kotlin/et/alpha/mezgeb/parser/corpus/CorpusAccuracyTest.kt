package et.alpha.mezgeb.parser.corpus

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The accuracy harness, run against `corpus/train/` and `corpus/noise.json`.
 *
 * `corpus/holdout/` is deliberately untouched here. It is run once, separately, by
 * [CorpusHoldoutTest], and never tuned against — a number you tuned against measures
 * only that you tuned against it.
 */
class CorpusAccuracyTest {

    private val trainAndNoise: List<LoadedCorpusFile> by lazy {
        CorpusLoader.load().filter { it.split != CorpusSplit.HOLDOUT }
    }

    private fun requireCorpus() {
        assumeTrue(
            trainAndNoise.any { it.file.samples.isNotEmpty() },
            "Corpus not collected yet — see docs/04-SMS-SAMPLE-COLLECTION.md",
        )
    }

    @Test
    @DisplayName("train accuracy is at least 95% with no wrong answers")
    fun `train corpus parses accurately`() {
        requireCorpus()
        val report = CorpusAccuracy.evaluate(trainAndNoise)
        println(CorpusAccuracy.format(report, "TRAIN + NOISE"))

        if (report.falsePositives.isNotEmpty()) {
            fail(
                "${report.falsePositives.size} false positive(s). A noise sample produced a " +
                    "transaction: that silently writes a wrong number into a ledger, which is " +
                    "worse than not reading the message at all.",
            )
        }

        val wrong = report.mismatches.filter { it.fatal }
        if (wrong.isNotEmpty()) {
            fail(
                "${wrong.size} wrong value(s) extracted. A miss is acceptable — it lands in the " +
                    "review queue. A wrong answer is not.",
            )
        }

        assertTrue(
            report.overallAccuracy >= 0.95,
            "overall accuracy %.2f%% is below the 95%% floor".format(report.overallAccuracy * 100),
        )
    }

    @Test
    @DisplayName("no noise sample ever produces a transaction")
    fun `noise never yields a success`() {
        requireCorpus()
        val noise = trainAndNoise.filter { it.split == CorpusSplit.NOISE }
        assumeTrue(noise.isNotEmpty(), "no noise samples collected")

        val report = CorpusAccuracy.evaluate(noise)

        assertTrue(
            report.falsePositives.isEmpty(),
            "false positives on noise: " + report.falsePositives.joinToString { it.sampleId },
        )
    }

    @Test
    @DisplayName("every field the parser asserts is correct (precision 100%)")
    fun `no field is ever extracted wrongly`() {
        requireCorpus()
        val report = CorpusAccuracy.evaluate(trainAndNoise)

        val imprecise = report.perField.filterValues { it.precision < 1.0 }
        assertTrue(
            imprecise.isEmpty(),
            "fields extracted with wrong values: " +
                imprecise.entries.joinToString { "${it.key} (${"%.1f".format(it.value.precision * 100)}%)" },
        )
    }
}
