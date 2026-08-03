package et.alpha.mezgeb.parser.corpus

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.fail

/**
 * Guards the corpus itself, before any parsing exists to run against it.
 *
 * Two jobs:
 *  1. **Privacy.** Nothing resembling a real phone number or account number may reach
 *     the repository. A leak in a portfolio project undoes the whole point of the
 *     project, and git history is forever — so this fails the build rather than warns.
 *  2. **Labelling discipline.** Expectations are hand-written by reading the message.
 *     Structural mistakes (a typo'd direction, a missing amount, a duplicate id) are
 *     cheap to catch here and expensive to debug inside an accuracy harness.
 *
 * Every test skips cleanly while the corpus is still empty, so this can live in the
 * repo from now and start protecting the moment the first sample lands.
 */
class CorpusValidationTest {

    private val corpus: List<LoadedCorpusFile> by lazy { CorpusLoader.load() }

    private fun requireCorpus() {
        assumeTrue(
            corpus.any { it.file.samples.isNotEmpty() },
            "Corpus not collected yet — see docs/04-SMS-SAMPLE-COLLECTION.md",
        )
    }

    // --- Privacy ------------------------------------------------------------------

    /** `+251912345678` redacts to `+251900000001`: keep the shape, drop the identity. */
    private val redactedPhone = Regex("""\+251900000\d{3}""")
    private val anyEthiopianIntl = Regex("""\+?251[79]\d{8}""")
    private val ethiopianLocal = Regex("""(?<!\d)0[79]\d{8}(?!\d)""")
    private val longDigitRun = Regex("""(?<!\d)\d{10,}(?!\d)""")

    /**
     * A purely numeric transaction reference in redacted form.
     *
     * Some providers number their references with digits alone, which is
     * indistinguishable from an account number by shape — so the corpus cannot simply
     * allow "whatever the sample declared", which is what it used to do and how fifteen
     * real-shaped references reached the repository.
     *
     * They redact the way phone numbers do: into a range nothing real occupies. A
     * reference is the sample's sequence number left-padded with zeros to the original
     * length, so the length the parser learns is preserved, leading zeros still get
     * exercised, and the underlying value is small enough that no real twelve-digit
     * reference could collide with it.
     */
    private val redactedNumericReference = Regex("""0{4,}\d{1,3}""")

    @Test
    @DisplayName("no message body contains an unredacted phone number")
    fun `bodies carry no real phone numbers`() {
        requireCorpus()
        val leaks = mutableListOf<String>()

        forEachSample { path, sample ->
            ethiopianLocal.findAll(sample.body).forEach {
                leaks += "$path/${sample.id}: local-format number '${it.value}'"
            }
            anyEthiopianIntl.findAll(sample.body).forEach { match ->
                val normalised = if (match.value.startsWith("+")) match.value else "+${match.value}"
                if (!redactedPhone.matches(normalised)) {
                    leaks += "$path/${sample.id}: international number '${match.value}'"
                }
            }
        }

        if (leaks.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Unredacted phone numbers found in the corpus.")
                    appendLine("Redact to +251900000NNN, preserving length and format, then re-run.")
                    appendLine("If any of these were ever committed, the history needs rewriting too.")
                    leaks.forEach { appendLine("  - $it") }
                },
            )
        }
    }

    /** A receipt URL's path and query, which is where the identifying part lives. */
    private val urlPath = Regex("""https?://[^/\s]+(/\S*)""")

    /** What a redacted URL token looks like: filler, then a sequence number. */
    private val redactedUrlToken = Regex("""x+\d{3}""")

    @Test
    @DisplayName("no receipt URL carries an unredacted path or query token")
    fun `urls carry no real tokens`() {
        requireCorpus()
        val leaks = mutableListOf<String>()

        forEachSample { path, sample ->
            urlPath.findAll(sample.body).forEach { url ->
                Regex("""[A-Za-z0-9]{6,}""").findAll(url.groupValues[1]).forEach { token ->
                    val t = token.value
                    val allowed = redactedUrlToken.matches(t) ||
                        t.all { it == '0' } ||
                        // The JSON escape for "&" survives decoding as u0026 here.
                        t.startsWith("u0026")
                    if (!allowed) leaks += "$path/${sample.id}: '$t'"
                }
            }
        }

        if (leaks.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Unredacted tokens inside receipt URLs.")
                    appendLine("A receipt link is a live handle on a real transaction, so every")
                    appendLine("path and query token of six characters or more is replaced with")
                    appendLine("filler plus a sequence number. The host is left alone on purpose.")
                    leaks.forEach { appendLine("  - $it") }
                },
            )
        }
    }

    @Test
    @DisplayName("no message body contains an unredacted account number")
    fun `bodies carry no long digit runs`() {
        requireCorpus()
        val leaks = mutableListOf<String>()

        forEachSample { path, sample ->
            // Some providers use a purely numeric transaction reference, which is
            // indistinguishable from an account number by shape alone.
            //
            // Declaring it as `providerTxnId` used to be enough on its own. That is a
            // guard which cannot fail: a real, unredacted reference is declared exactly
            // the same way a redacted one is, so the check blessed whatever it was
            // shown. It has to be *and* — declared as the reference, and in redacted
            // form.
            val declaredReference = sample.expected?.providerTxnId
            longDigitRun.findAll(sample.body).forEach { match ->
                val digits = match.value
                val allowed = digits.all { it == '0' } ||
                    redactedPhone.matches("+$digits") ||
                    (digits == declaredReference && redactedNumericReference.matches(digits))
                if (!allowed) {
                    leaks += "$path/${sample.id}: '$digits' (${digits.length} digits)"
                }
            }
        }

        if (leaks.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Possible unredacted account numbers or references in the corpus.")
                    appendLine("Account numbers redact to same-length zeros; amounts keep their")
                    appendLine("separators; a purely numeric transaction reference must both be")
                    appendLine("declared as providerTxnId and be in redacted form — the sample's")
                    appendLine("sequence number, left-padded with zeros to the original length.")
                    leaks.forEach { appendLine("  - $it") }
                },
            )
        }
    }

    @Test
    @DisplayName("expected phone numbers are in redacted E.164 form")
    fun `expected phones are redacted and normalised`() {
        requireCorpus()
        val bad = mutableListOf<String>()

        forEachSample { path, sample ->
            val phone = sample.expected?.counterpartyPhone ?: return@forEachSample
            if (!redactedPhone.matches(phone)) {
                bad += "$path/${sample.id}: '$phone'"
            }
        }

        if (bad.isNotEmpty()) {
            fail("counterpartyPhone must be redacted E.164 (+251900000NNN):\n" + bad.joinToString("\n") { "  - $it" })
        }
    }

    // --- Structure and labelling ---------------------------------------------------

    @Test
    fun `sample ids are unique across the whole corpus`() {
        requireCorpus()
        val seen = mutableMapOf<String, String>()
        val clashes = mutableListOf<String>()

        forEachSample { path, sample ->
            val previous = seen.put(sample.id, path)
            if (previous != null) clashes += "'${sample.id}' in both $previous and $path"
        }

        if (clashes.isNotEmpty()) {
            fail("duplicate sample ids:\n" + clashes.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `channels and directions use the project vocabulary`() {
        requireCorpus()
        val bad = mutableListOf<String>()

        corpus.forEach { loaded ->
            if (loaded.file.channel !in CorpusVocabulary.CHANNELS) {
                bad += "${loaded.path}: channel '${loaded.file.channel}' " +
                    "(expected one of ${CorpusVocabulary.CHANNELS})"
            }
            loaded.file.samples.forEach { sample ->
                val direction = sample.expected?.direction
                if (direction != null && direction !in CorpusVocabulary.DIRECTIONS) {
                    bad += "${loaded.path}/${sample.id}: direction '$direction'"
                }
            }
        }

        if (bad.isNotEmpty()) fail(bad.joinToString("\n") { "  - $it" })
    }

    @Test
    fun `ids and bodies are present and non-blank`() {
        requireCorpus()
        val bad = mutableListOf<String>()

        corpus.forEach { loaded ->
            loaded.file.samples.forEachIndexed { index, sample ->
                if (sample.id.isBlank()) bad += "${loaded.path}[$index]: blank id"
                if (sample.body.isBlank()) bad += "${loaded.path}/${sample.id}: blank body"
            }
        }

        if (bad.isNotEmpty()) fail(bad.joinToString("\n") { "  - $it" })
    }

    @Test
    @DisplayName("noise samples carry no expectations")
    fun `noise samples are labelled null`() {
        requireCorpus()
        val bad = corpus.filter { it.split == CorpusSplit.NOISE }
            .flatMap { loaded ->
                loaded.file.samples.filter { it.expected != null }
                    .map { "${loaded.path}/${it.id}" }
            }

        if (bad.isNotEmpty()) {
            fail(
                "noise samples must have \"expected\": null — the parser must return " +
                    "Ignored or Unmatched for them:\n" + bad.joinToString("\n") { "  - $it" },
            )
        }
    }

    @Test
    @DisplayName("financial samples are labelled with at least a direction and amount")
    fun `financial samples carry the required fields`() {
        requireCorpus()
        val bad = mutableListOf<String>()

        corpus.filter { it.split != CorpusSplit.NOISE }.forEach { loaded ->
            loaded.file.samples.forEach { sample ->
                val expected = sample.expected
                if (expected == null) {
                    bad += "${loaded.path}/${sample.id}: no expectations " +
                        "(move it to noise.json if that is deliberate)"
                    return@forEach
                }
                if (expected.direction == null) bad += "${loaded.path}/${sample.id}: no direction"
                if (expected.amountMinor == null) bad += "${loaded.path}/${sample.id}: no amountMinor"
                if (expected.amountMinor != null && expected.amountMinor < 0) {
                    bad += "${loaded.path}/${sample.id}: negative amountMinor " +
                        "(direction carries the sign)"
                }
            }
        }

        if (bad.isNotEmpty()) fail(bad.joinToString("\n") { "  - $it" })
    }

    @Test
    fun `timestamps parse as ISO-8601 with an offset`() {
        requireCorpus()
        val bad = mutableListOf<String>()

        forEachSample { path, sample ->
            val raw = sample.expected?.occurredAt ?: return@forEachSample
            runCatching { OffsetDateTime.parse(raw) }
                .onFailure { bad += "$path/${sample.id}: '$raw' (expected e.g. 2026-07-14T09:22:00+03:00)" }
        }

        if (bad.isNotEmpty()) fail(bad.joinToString("\n") { "  - $it" })
    }

    // --- Reporting ------------------------------------------------------------------

    @Test
    @DisplayName("print the corpus inventory")
    fun `report corpus composition`() {
        requireCorpus()

        val bySplit = corpus.groupBy { it.split }
        val financial = corpus.filter { it.split != CorpusSplit.NOISE }
        val totalFinancial = financial.sumOf { it.file.samples.size }
        val totalNoise = bySplit[CorpusSplit.NOISE].orEmpty().sumOf { it.file.samples.size }
        val senders = financial.map { it.file.sender }.distinct()

        println(buildString {
            appendLine()
            appendLine("Corpus inventory")
            appendLine("-".repeat(62))
            appendLine("%-22s %-10s %-10s %s".format("SENDER", "SPLIT", "CHANNEL", "SAMPLES"))
            corpus.sortedWith(compareBy({ it.split }, { it.file.sender })).forEach { loaded ->
                appendLine(
                    "%-22s %-10s %-10s %d".format(
                        loaded.file.sender.take(22),
                        loaded.split.name.lowercase(),
                        loaded.file.channel.take(10),
                        loaded.file.samples.size,
                    ),
                )
            }
            appendLine("-".repeat(62))
            appendLine("financial samples : $totalFinancial across ${senders.size} senders")
            appendLine("noise samples     : $totalNoise")
            val trainCount = bySplit[CorpusSplit.TRAIN].orEmpty().sumOf { it.file.samples.size }
            val holdoutCount = bySplit[CorpusSplit.HOLDOUT].orEmpty().sumOf { it.file.samples.size }
            if (trainCount + holdoutCount > 0) {
                val holdoutPct = holdoutCount * 100.0 / (trainCount + holdoutCount)
                appendLine("train / holdout   : $trainCount / $holdoutCount (%.0f%% holdout)".format(holdoutPct))
            }
            appendLine()
            appendLine("Targets from docs/04 — these are guidance, not assertions:")
            appendLine("  ${check(totalFinancial >= 150)} >= 150 financial samples")
            appendLine("  ${check(senders.size >= 4)} >= 4 distinct senders")
            appendLine("  ${check(totalNoise >= 30)} >= 30 noise samples")
            appendLine("  ${check(holdoutCount > 0)} holdout split present")
        })
    }

    private fun check(passed: Boolean) = if (passed) "[x]" else "[ ]"

    private inline fun forEachSample(action: (path: String, sample: CorpusSample) -> Unit) {
        corpus.forEach { loaded -> loaded.file.samples.forEach { action(loaded.path, it) } }
    }
}
