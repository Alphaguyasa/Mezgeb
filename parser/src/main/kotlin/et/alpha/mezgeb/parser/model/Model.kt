package et.alpha.mezgeb.parser.model

/**
 * The semantic fields a rule can extract from a message.
 *
 * A rule maps its own named capture groups onto these, so the group in the regex can
 * be called whatever reads well while the engine still knows what it means.
 */
enum class SemanticField {
    AMOUNT,
    DIRECTION,
    COUNTERPARTY_NAME,
    COUNTERPARTY_PHONE,
    PROVIDER_TXN_ID,
    BALANCE_AFTER,
    TIMESTAMP,
}

/** Where the money moved. Mirrors the app's `Channel`; duplicated so `:parser` stays pure JVM. */
enum class Channel {
    TELEBIRR,
    CBE_BIRR,
    BANK,
    CASH,
    OTHER,
}

enum class Direction {
    IN,
    OUT,
}

/**
 * The result of a successful extraction.
 *
 * Money is `Long` santim. A null field means the message genuinely did not carry that
 * value — the parser never invents one.
 */
data class ParsedFields(
    val direction: Direction,
    val amountMinor: Long,
    val channel: Channel,
    val counterpartyName: String? = null,
    /** Normalised to +251 E.164. */
    val counterpartyPhone: String? = null,
    val providerTxnId: String? = null,
    val balanceAfterMinor: Long? = null,
    /** Epoch millis UTC. Falls back to the message's receivedAt when the body has no timestamp. */
    val occurredAt: Long,
)

/** Why a message produced no transaction. */
enum class UnmatchedReason {
    /** No rule's sender pattern matched. */
    NO_RULE_FOR_SENDER,

    /** The sender had rules, but no body pattern matched. */
    NO_PATTERN_MATCHED,

    /** A pattern matched but a required field could not be normalised. */
    FIELD_EXTRACTION_FAILED,

    /** A pattern matched but carried no amount, so there is nothing to record. */
    NO_AMOUNT,
}

/**
 * What the parser concluded about a message.
 *
 * [Ignored] exists so that promotional traffic from a financial sender does not land
 * in the review queue. Without it the queue fills with noise and the user learns to
 * ignore it, which defeats the point of having one.
 */
sealed interface ParseOutcome {

    data class Success(
        val fields: ParsedFields,
        val ruleId: String,
        val confidence: Float,
    ) : ParseOutcome

    data class Unmatched(val reason: UnmatchedReason) : ParseOutcome

    /** Two rules of equal priority matched and disagreed. Never guess between them. */
    data class Ambiguous(val candidates: List<Success>) : ParseOutcome

    /** The sender is not on the financial allowlist, or the message is not transactional. */
    data object Ignored : ParseOutcome
}
