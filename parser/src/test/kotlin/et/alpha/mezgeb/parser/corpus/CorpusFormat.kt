package et.alpha.mezgeb.parser.corpus

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the message corpus, as specified in
 * `docs/04-SMS-SAMPLE-COLLECTION.md`.
 *
 * Deliberately plain strings for `channel` and `direction` rather than the parser's
 * own enums: the corpus is authored by hand before the parser exists, and a typo in a
 * sample file should surface as a readable validation failure rather than a
 * deserialization crash. [CorpusValidationTest] checks the values.
 */
@Serializable
data class CorpusFile(
    val sender: String,
    val channel: String,
    val collectedAt: String,
    val samples: List<CorpusSample> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class CorpusSample(
    val id: String,
    /** Redacted message text, exactly as received apart from the redactions. */
    val body: String,
    /**
     * Hand-labelled expectations. `null` means this is a noise sample: the parser must
     * return Ignored or Unmatched for it, and any Success is a false positive.
     *
     * A field left out means the message genuinely does not contain it, and the parser
     * must return null rather than inventing a value.
     */
    val expected: ExpectedFields? = null,
    val notes: String? = null,
)

@Serializable
data class ExpectedFields(
    val direction: String? = null,
    val amountMinor: Long? = null,
    val counterpartyName: String? = null,
    val counterpartyPhone: String? = null,
    val providerTxnId: String? = null,
    val balanceAfterMinor: Long? = null,
    /** ISO-8601 with offset, e.g. 2026-07-14T09:22:00+03:00. */
    @SerialName("occurredAt") val occurredAt: String? = null,
)

/** Which half of the split a file came from. */
enum class CorpusSplit { TRAIN, HOLDOUT, NOISE }

data class LoadedCorpusFile(
    val split: CorpusSplit,
    val path: String,
    val file: CorpusFile,
)

object CorpusVocabulary {
    val CHANNELS = setOf("TELEBIRR", "CBE_BIRR", "BANK", "CASH", "OTHER")
    val DIRECTIONS = setOf("IN", "OUT")
}
