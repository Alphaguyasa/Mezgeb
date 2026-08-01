package et.alpha.mezgeb.domain.model

/** Where a [RawMessage] came from. */
enum class CaptureSource {
    NOTIFICATION,
    SMS_IMPORT,
    MANUAL,
}

/** How far a [RawMessage] got through the parser. */
enum class ParseState {
    PARSED,
    UNMATCHED,
    IGNORED,
    RESOLVED_MANUALLY,
}

/**
 * What a [CreditEntry] does to a customer's balance. The amount is always stored
 * positive; the kind carries the sign.
 */
enum class CreditKind {
    CREDIT_GIVEN,
    REPAYMENT,
    ADJUSTMENT,
}

enum class CategoryKind {
    INCOME,
    EXPENSE,
}

/** Which part of a transaction a [CategoryRule] inspects. */
enum class MatchField {
    COUNTERPARTY_NAME,
    COUNTERPARTY_PHONE,
    RAW_BODY,
}

enum class MatchType {
    CONTAINS,
    EQUALS,
    REGEX,
}

enum class SessionStatus {
    OPEN,
    CLOSED,
}
