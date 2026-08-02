package et.alpha.mezgeb.parser.induce

import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.Direction
import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.SenderAllowlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleInducerTest {

    private val inducer = RuleInducer()

    // --- Validation ------------------------------------------------------------------

    @Test
    fun `no spans is not a rule`() {
        val outcome = inducer.induce(SENDER, BODY, emptyList())
        assertEquals(InductionFailure.NO_SPANS, (outcome as InductionOutcome.Failed).reason)
    }

    @Test
    fun `a rule without an amount would record nothing`() {
        val outcome = inducer.induce(SENDER, BODY, listOf(span(SemanticField.COUNTERPARTY_NAME, "ABEBE")))
        assertEquals(InductionFailure.NO_AMOUNT_SPAN, (outcome as InductionOutcome.Failed).reason)
    }

    @Test
    fun `overlapping spans are rejected rather than guessed at`() {
        val outcome = inducer.induce(
            SENDER,
            BODY,
            listOf(
                TaggedSpan(SemanticField.AMOUNT, 0, 20),
                TaggedSpan(SemanticField.COUNTERPARTY_NAME, 10, 30),
            ),
        )
        assertEquals(InductionFailure.OVERLAPPING_SPANS, (outcome as InductionOutcome.Failed).reason)
    }

    @Test
    fun `the same field cannot be tagged twice`() {
        val outcome = inducer.induce(
            SENDER,
            BODY,
            listOf(
                TaggedSpan(SemanticField.AMOUNT, 0, 3),
                TaggedSpan(SemanticField.AMOUNT, 5, 8),
            ),
        )
        assertEquals(InductionFailure.DUPLICATE_FIELD, (outcome as InductionOutcome.Failed).reason)
    }

    @Test
    fun `a span outside the body is rejected`() {
        val outcome = inducer.induce(
            SENDER,
            BODY,
            listOf(TaggedSpan(SemanticField.AMOUNT, 0, BODY.length + 5)),
        )
        assertEquals(InductionFailure.SPAN_OUT_OF_BOUNDS, (outcome as InductionOutcome.Failed).reason)
    }

    // --- Generalisation --------------------------------------------------------------

    @Test
    fun `digit runs in literals are generalised`() {
        // The whole point of the exercise: without this, a rule learned from a message
        // sent on the fourteenth only ever matches messages sent on the fourteenth.
        val generalised = inducer.generaliseLiteral(" on 14/03 at 09:22")

        assertTrue(generalised.contains("""\d+"""), generalised)
        assertFalse(generalised.contains("14"), generalised)
        assertFalse(generalised.contains("09"), generalised)
    }

    @Test
    fun `whitespace runs are generalised`() {
        val generalised = inducer.generaliseLiteral("received  from")

        assertTrue(generalised.contains("""\s+"""), generalised)
        assertTrue(Regex(generalised).matches("received from"))
        assertTrue(Regex(generalised).matches("received     from"))
    }

    @Test
    fun `regex metacharacters in a literal are escaped, not interpreted`() {
        val generalised = inducer.generaliseLiteral("balance (ETB)?")

        // Without escaping, "(ETB)?" would become an optional group and the rule would
        // match messages that never mentioned a balance at all.
        assertTrue(Regex(generalised).matches("balance (ETB)?"))
        assertFalse(Regex(generalised).matches("balance "))
    }

    // --- Classification --------------------------------------------------------------

    @Test
    fun `the tapped field decides the class, because content alone is ambiguous`() {
        // A bare ten-digit phone number and a large amount look identical.
        assertEquals(SpanClass.PHONE, inducer.classify(SemanticField.COUNTERPARTY_PHONE, "0911234567"))
        assertEquals(SpanClass.CURRENCY, inducer.classify(SemanticField.AMOUNT, "1,200.50"))
        assertEquals(
            SpanClass.ALPHANUMERIC_ID,
            inducer.classify(SemanticField.PROVIDER_TXN_ID, "CJ4T5X9AB1"),
        )
        assertEquals(SpanClass.FREE_TEXT, inducer.classify(SemanticField.COUNTERPARTY_NAME, "ABEBE K"))
    }

    @Test
    fun `a field tagged on text that does not fit its shape falls back to free text`() {
        // The user is not wrong about which field it is; the app is wrong about what
        // that field looks like at this provider.
        assertEquals(SpanClass.FREE_TEXT, inducer.classify(SemanticField.AMOUNT, "four hundred"))
    }

    // --- Induction end to end ---------------------------------------------------------

    @Test
    fun `an induced rule reads the message it was taught from`() {
        val induced = inducedRule()

        val outcome = parserFor(induced).parse(SENDER, BODY, RECEIVED_AT)

        val success = assertIs<ParseOutcome.Success>(outcome)
        assertEquals(45_000L, success.fields.amountMinor)
        assertEquals("ABEBE", success.fields.counterpartyName)
        assertEquals("CJ4T5X9AB1", success.fields.providerTxnId)
        assertEquals(Direction.IN, success.fields.direction)
    }

    @Test
    fun `an induced rule reads other messages of the same shape`() {
        val induced = inducedRule()
        val parser = parserFor(induced)

        val other = "You have received ETB 12.50 from KEBEDE on 02/07 at 11:03. " +
            "Your transaction number is QQ88ZZ1177."
        val outcome = parser.parse(SENDER, other, RECEIVED_AT)

        val success = assertIs<ParseOutcome.Success>(outcome)
        assertEquals(1_250L, success.fields.amountMinor)
        assertEquals("KEBEDE", success.fields.counterpartyName)
        assertEquals("QQ88ZZ1177", success.fields.providerTxnId)
    }

    @Test
    fun `an induced rule declines a message of a different shape`() {
        val parser = parserFor(inducedRule())

        val different = "Your account has been debited with ETB 300.00 for a service charge."

        assertIs<ParseOutcome.Unmatched>(parser.parse(SENDER, different, RECEIVED_AT))
    }

    @Test
    fun `nothing is degraded when nothing needs to be`() {
        assertNull(inducedRule().degradedField)
    }

    // --- Evaluation --------------------------------------------------------------------

    @Test
    fun `evaluate counts hits and shows the user what was extracted`() {
        val induced = inducedRule()

        val report = inducer.evaluate(
            rule = induced.rule,
            candidates = listOf(
                candidate("m1", BODY),
                candidate("m2", "You have received ETB 12.50 from KEBEDE on 02/07 at 11:03. Your transaction number is QQ88ZZ1177."),
                candidate("m3", "Dear customer, beware of fraud. Never share your PIN."),
            ),
        )

        assertEquals(2, report.matched)
        assertEquals(3, report.total)
        assertEquals(2, report.samples.size)
        assertEquals(45_000L, report.samples.first().fields.amountMinor)
    }

    @Test
    fun `evaluate shows at most three samples however many matched`() {
        val induced = inducedRule()
        val candidates = (1..10).map { candidate("m$it", BODY.replace("450.00", "$it.00")) }

        val report = inducer.evaluate(induced.rule, candidates)

        assertEquals(10, report.matched)
        assertEquals(3, report.samples.size)
    }

    @Test
    fun `a rule that reads almost nothing is flagged as too specific`() {
        val induced = inducedRule()
        val candidates = listOf(candidate("m1", BODY)) +
            (2..8).map { candidate("m$it", "Some entirely different template $it") }

        val report = inducer.evaluate(induced.rule, candidates)

        assertTrue(report.looksTooSpecific)
        assertTrue(report.hitRate < 0.6f)
    }

    @Test
    fun `too few samples is not enough to judge a rule by`() {
        val induced = inducedRule()
        val report = inducer.evaluate(
            induced.rule,
            listOf(candidate("m1", BODY), candidate("m2", "different")),
        )

        // One in two looks bad and means nothing.
        assertFalse(report.looksTooSpecific)
    }

    @Test
    fun `a rule that already reads these messages is reported as a conflict`() {
        val existing = inducedRule(ruleId = "existing").rule

        val report = inducer.evaluate(
            rule = inducedRule(ruleId = "new").rule,
            candidates = listOf(candidate("m1", BODY)),
            existingRules = listOf(existing),
        )

        assertEquals(listOf("existing"), report.conflicts.map { it.id })
    }

    @Test
    fun `a disabled rule is not a conflict`() {
        val existing = inducedRule(ruleId = "existing").rule.copy(isEnabled = false)

        val report = inducer.evaluate(
            rule = inducedRule(ruleId = "new").rule,
            candidates = listOf(candidate("m1", BODY)),
            existingRules = listOf(existing),
        )

        assertTrue(report.conflicts.isEmpty())
    }

    // --- Helpers -----------------------------------------------------------------------

    private fun inducedRule(ruleId: String = "induced"): InducedRule {
        val outcome = inducer.induce(
            sender = SENDER,
            body = BODY,
            spans = listOf(
                span(SemanticField.AMOUNT, "450.00"),
                span(SemanticField.COUNTERPARTY_NAME, "ABEBE"),
                span(SemanticField.PROVIDER_TXN_ID, "CJ4T5X9AB1"),
            ),
            ruleId = ruleId,
        )
        return assertIs<InductionOutcome.Success>(outcome).induced
    }

    private fun parserFor(induced: InducedRule) = MessageParser(
        rules = listOf(induced.rule),
        allowlist = SenderAllowlist(listOf(induced.rule.senderPattern)),
    )

    private fun span(field: SemanticField, text: String): TaggedSpan {
        val start = BODY.indexOf(text)
        require(start >= 0) { "'$text' is not in the test body" }
        return TaggedSpan(field, start, start + text.length)
    }

    private fun candidate(id: String, body: String) =
        InductionCandidate(id = id, sender = SENDER, body = body, receivedAt = RECEIVED_AT)

    private companion object {
        const val SENDER = "127"
        const val RECEIVED_AT = 1_785_000_000_000L
        const val BODY = "You have received ETB 450.00 from ABEBE on 14/03 at 09:22. " +
            "Your transaction number is CJ4T5X9AB1."
    }
}
