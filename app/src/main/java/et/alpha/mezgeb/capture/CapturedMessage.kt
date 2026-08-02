package et.alpha.mezgeb.capture

import et.alpha.mezgeb.domain.model.CaptureSource

/**
 * A message as it arrives at the capture funnel, before anything has been decided
 * about it.
 *
 * Deliberately source-agnostic: a notification, an SMS inbox row and (later) a
 * hand-typed message all become one of these, so [CaptureCoordinator] has exactly
 * one input shape and deduplication has exactly one place to be wrong.
 */
data class CapturedMessage(
    /** As posted, before normalisation. Rules and the hash normalise it themselves. */
    val sender: String,
    /** Verbatim. Never logged — see [et.alpha.mezgeb.util.Redactor]. */
    val body: String,
    /** Epoch millis UTC. Notification post time, or the inbox row's `date`. */
    val receivedAt: Long,
    val source: CaptureSource,
)

/**
 * What the funnel did with a message.
 *
 * Returned rather than logged so that callers — the SMS importer especially — can
 * count outcomes and show a shop owner "412 imported, 38 already known" instead of
 * a spinner that stops.
 */
sealed interface CaptureOutcome {

    /** Parsed into a transaction. */
    data class Recorded(val rawMessageId: String, val transactionId: String) : CaptureOutcome

    /** Stored, but no rule could read it. It is in the review queue. */
    data class Queued(val rawMessageId: String, val reason: QueueReason) : CaptureOutcome

    /** Stored and declined: not a transactional message. Never shown to the user. */
    data class Ignored(val rawMessageId: String) : CaptureOutcome

    /**
     * Already captured. Either the content hash collided (tier one) or the parsed
     * provider transaction id already belonged to a transaction (tier two).
     */
    data class Duplicate(val tier: DedupTier) : CaptureOutcome
}

/**
 * What a re-parse of the review queue changed.
 *
 * Counted so the ledger can say "7 messages could now be read" rather than
 * refreshing and looking like it did nothing.
 */
data class ReparseSummary(
    val scanned: Int = 0,
    val recorded: Int = 0,
    /** Parsed, but the payment was already in the ledger from the other source. */
    val duplicates: Int = 0,
    /** Now recognised as not a transaction at all. */
    val ignored: Int = 0,
) {
    val changed: Int get() = recorded + duplicates + ignored
}

/** Why a message is waiting in the review queue. */
enum class QueueReason {
    /** No rule's sender pattern matched, or no body pattern matched. */
    NO_RULE_MATCHED,

    /** A rule matched but a required field would not normalise. */
    EXTRACTION_FAILED,

    /** Two equally authoritative rules disagreed. The engine never guesses. */
    AMBIGUOUS,
}

/**
 * Which of the two dedup keys rejected a message.
 *
 * Worth distinguishing: [CONTENT_HASH] hits are the ordinary case of one payment
 * seen twice, while [PROVIDER_TXN_ID] hits mean the two sources rendered the same
 * payment as different text — the interesting case, and the one that tells you the
 * hash normalisation is not enough on its own.
 */
enum class DedupTier {
    PROVIDER_TXN_ID,
    CONTENT_HASH,
}
