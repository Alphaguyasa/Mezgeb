package et.alpha.mezgeb.capture

import et.alpha.mezgeb.domain.model.CategoryRule
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType

/**
 * Picks a category for a freshly captured transaction.
 *
 * Rules are tried in the order given — the DAO hands them over sorted by priority
 * descending — and the first match wins. A rule whose pattern will not compile is
 * skipped rather than thrown: a bad regex is a user's typo in the rules screen, and
 * a typo must not stop a payment being recorded.
 */
object CategoryRuleMatcher {

    fun categoryIdFor(
        rules: List<CategoryRule>,
        counterpartyName: String?,
        counterpartyPhone: String?,
        body: String,
    ): String? = rules.firstOrNull { rule ->
        val subject = when (rule.matchField) {
            MatchField.COUNTERPARTY_NAME -> counterpartyName
            MatchField.COUNTERPARTY_PHONE -> counterpartyPhone
            MatchField.RAW_BODY -> body
        }
        subject != null && matches(rule, subject)
    }?.categoryId

    private fun matches(rule: CategoryRule, subject: String): Boolean = when (rule.matchType) {
        MatchType.CONTAINS -> subject.contains(rule.pattern, ignoreCase = true)
        MatchType.EQUALS -> subject.equals(rule.pattern, ignoreCase = true)
        MatchType.REGEX -> runCatching {
            Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(subject)
        }.getOrDefault(false)
    }
}
