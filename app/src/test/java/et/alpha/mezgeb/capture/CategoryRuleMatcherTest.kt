package et.alpha.mezgeb.capture

import et.alpha.mezgeb.domain.model.CategoryRule
import et.alpha.mezgeb.domain.model.MatchField
import et.alpha.mezgeb.domain.model.MatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryRuleMatcherTest {

    @Test
    fun `no rules means no category`() {
        assertNull(
            CategoryRuleMatcher.categoryIdFor(
                rules = emptyList(),
                counterpartyName = "ABEBE",
                counterpartyPhone = "+251900000001",
                body = "you have received ETB 450.00",
            ),
        )
    }

    @Test
    fun `contains matches without regard to case`() {
        val rules = listOf(rule("r1", MatchField.COUNTERPARTY_NAME, MatchType.CONTAINS, "abebe"))

        assertEquals(
            "cat_sales",
            CategoryRuleMatcher.categoryIdFor(rules, "ABEBE TRADING", null, ""),
        )
    }

    @Test
    fun `equals does not match a substring`() {
        val rules = listOf(rule("r1", MatchField.COUNTERPARTY_NAME, MatchType.EQUALS, "abebe"))

        assertNull(CategoryRuleMatcher.categoryIdFor(rules, "ABEBE TRADING", null, ""))
        assertEquals("cat_sales", CategoryRuleMatcher.categoryIdFor(rules, "Abebe", null, ""))
    }

    @Test
    fun `the first matching rule wins`() {
        // The DAO hands rules over already sorted by priority, so order is the contract.
        val rules = listOf(
            rule("r1", MatchField.RAW_BODY, MatchType.CONTAINS, "received", "cat_sales"),
            rule("r2", MatchField.RAW_BODY, MatchType.CONTAINS, "etb", "cat_other"),
        )

        assertEquals(
            "cat_sales",
            CategoryRuleMatcher.categoryIdFor(rules, null, null, "you have received ETB 450.00"),
        )
    }

    @Test
    fun `a rule with an uncompilable pattern is skipped rather than thrown`() {
        // Category rules are user input from Session 7's rules screen. A typo must not
        // stop a payment being recorded.
        val rules = listOf(
            rule("bad", MatchField.RAW_BODY, MatchType.REGEX, "(unclosed", "cat_sales"),
            rule("good", MatchField.RAW_BODY, MatchType.CONTAINS, "received", "cat_other"),
        )

        assertEquals(
            "cat_other",
            CategoryRuleMatcher.categoryIdFor(rules, null, null, "you have received ETB 450.00"),
        )
    }

    @Test
    fun `a rule cannot match a field the message did not carry`() {
        val rules = listOf(rule("r1", MatchField.COUNTERPARTY_PHONE, MatchType.CONTAINS, "251"))

        assertNull(CategoryRuleMatcher.categoryIdFor(rules, "ABEBE", null, "body"))
    }

    private fun rule(
        id: String,
        field: MatchField,
        type: MatchType,
        pattern: String,
        categoryId: String = "cat_sales",
    ) = CategoryRule(
        id = id,
        matchField = field,
        matchType = type,
        pattern = pattern,
        categoryId = categoryId,
        priority = 0,
    )
}
