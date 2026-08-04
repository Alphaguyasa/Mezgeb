package et.alpha.mezgeb.domain.model

/**
 * Where the money moved. Part of the fixed domain vocabulary in CONVENTIONS.md — do not
 * introduce synonyms or extra members without updating that table first.
 */
enum class Channel {
    TELEBIRR,
    CBE_BIRR,
    BANK,
    CASH,
    OTHER,
}
