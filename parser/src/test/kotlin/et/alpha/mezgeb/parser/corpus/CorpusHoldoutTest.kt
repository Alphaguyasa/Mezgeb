package et.alpha.mezgeb.parser.corpus

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The holdout run.
 *
 * Deliberately skipped unless `-Dmezgeb.holdout=true` is passed. The holdout set is
 * only worth anything while nobody has tuned against it, and a test that runs on every
 * build is a test people tune against — usually without meaning to, by iterating until
 * it goes green.
 *
 * Run it once at the end of Session 2, and once more at the end of the project:
 *
 * ```
 * ./gradlew :parser:test --tests '*CorpusHoldoutTest*' -Dmezgeb.holdout=true
 * ```
 *
 * Record the number in NOTES.md with the date. Do not change a rule in response to it.
 */
class CorpusHoldoutTest {

    @Test
    @DisplayName("holdout accuracy — report only, never tune against this")
    fun `report holdout accuracy`() {
        assumeTrue(
            System.getProperty("mezgeb.holdout") == "true",
            "holdout run is opt-in: pass -Dmezgeb.holdout=true",
        )

        val holdout = CorpusLoader.load().filter { it.split == CorpusSplit.HOLDOUT }
        assumeTrue(holdout.any { it.file.samples.isNotEmpty() }, "no holdout samples collected")

        val report = CorpusAccuracy.evaluate(holdout)
        println(CorpusAccuracy.format(report, "HOLDOUT — reported, not tuned against"))

        // Intentionally no assertion on the accuracy figure. Asserting a floor here
        // would turn the holdout into another thing to tune until it passes, which is
        // exactly what it exists to prevent. False positives are still reported in the
        // table above and remain fatal in the train harness.
    }
}
