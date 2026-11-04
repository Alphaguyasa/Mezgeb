package et.alpha.mezgeb.domain.credit

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.CreditStatementLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared statement (ROADMAP R44). The text is what a customer reads in a dispute,
 * so the tests pin it down whole rather than checking that it contains a figure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatementTextTest {

    @Test
    fun `lines come out oldest first, with signs and the balance after each`() {
        val text = build(
            balanceMinor = 45_000,
            line("e1", CreditKind.CREDIT_GIVEN, 50_000, running = 50_000, day = 1),
            line("e2", CreditKind.REPAYMENT, 20_000, running = 30_000, day = 2),
            line("e3", CreditKind.ADJUSTMENT, 15_000, running = 45_000, day = 3),
        )

        assertEquals(
            """
            Statement for ABEBE

            DAY1 · Credit given · +500.00 ETB · balance 500.00 ETB
            DAY2 · Payment received · −200.00 ETB · balance 300.00 ETB
            DAY3 · Adjustment · +150.00 ETB · balance 450.00 ETB

            Owed to the shop: 450.00 ETB
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `the order given is kept, not re-sorted`() {
        val text = build(
            balanceMinor = 0,
            line("b", CreditKind.REPAYMENT, 100, running = 0, day = 9),
            line("a", CreditKind.CREDIT_GIVEN, 100, running = 100, day = 1),
        )

        assertTrue(text.indexOf("DAY9") < text.indexOf("DAY1"))
    }

    @Test
    fun `a shop that holds the customer's money says so, without a minus on the total`() {
        val text = build(
            balanceMinor = -2_000,
            line("e1", CreditKind.REPAYMENT, 2_000, running = -2_000, day = 1),
        )

        assertTrue(text.endsWith("Owed by the shop: 20.00 ETB"))
        // The running balance in the line keeps its sign: that is how it reads on paper.
        assertTrue(text.contains("balance −20.00 ETB"))
    }

    @Test
    fun `a settled balance says nothing is owed`() {
        val text = build(
            balanceMinor = 0,
            line("e1", CreditKind.CREDIT_GIVEN, 1_000, running = 1_000, day = 1),
            line("e2", CreditKind.REPAYMENT, 1_000, running = 0, day = 2),
        )

        assertTrue(text.endsWith("Nothing owed."))
        assertTrue(text.contains("balance 0.00 ETB"))
    }

    @Test
    fun `an empty statement still has a title and a closing line`() {
        assertEquals("Statement for ABEBE\n\nNothing yet\n\nNothing owed.", build(balanceMinor = 0))
    }

    @Test
    fun `large amounts are grouped, santim are kept`() {
        val text = build(
            balanceMinor = 123_456_789,
            line("e1", CreditKind.CREDIT_GIVEN, 123_456_789, running = 123_456_789, day = 1),
        )

        assertTrue(text.contains("+1,234,567.89 ETB · balance 1,234,567.89 ETB"))
    }

    @Test
    fun `notes are not shared`() {
        val text = build(
            balanceMinor = 1_000,
            line(
                "e1",
                CreditKind.CREDIT_GIVEN,
                1_000,
                running = 1_000,
                day = 1,
                note = "NOTE_PRIVATE",
            ),
        )

        assertFalse(text.contains("NOTE_PRIVATE"))
    }

    @Test
    fun `a name that looks like a placeholder or a format directive is printed as typed`() {
        val text = StatementText.build(
            customerName = "{amount} 100% %1\$s",
            lines = emptyList(),
            balanceMinor = 0,
            labels = LABELS,
            formatDay = { "unused" },
        )

        assertTrue(text.startsWith("Statement for {amount} 100% %1\$s\n"))
    }

    @Test
    fun `amharic labels lay out the same way`() {
        val text = StatementText.build(
            customerName = "ABEBE",
            lines = listOf(line("e1", CreditKind.CREDIT_GIVEN, 50_000, running = 50_000, day = 1)),
            balanceMinor = 50_000,
            labels = StatementText.Labels(
                title = "የ{name} የሒሳብ ማብራሪያ",
                kindGiven = "ዱቤ ተሰጥቷል",
                kindRepayment = "ክፍያ ተቀብለዋል",
                kindAdjustment = "ማስተካከያ",
                line = "{day} · {kind} · {amount} · ቀሪ {balance}",
                balanceOwed = "ለሱቁ የሚከፈል ቀሪ፦ {amount}",
                balanceShopOwes = "ሱቁ የሚከፍለው ቀሪ፦ {amount}",
                balanceSettled = "ምንም ዕዳ የለም።",
                empty = "እስካሁን ምንም የለም",
            ),
            formatDay = { "DAY$it" },
        )

        assertEquals(
            "የABEBE የሒሳብ ማብራሪያ\n\nDAY1 · ዱቤ ተሰጥቷል · +500.00 ETB · ቀሪ 500.00 ETB\n\n" +
                "ለሱቁ የሚከፈል ቀሪ፦ 500.00 ETB",
            text,
        )
    }

    @Test
    fun `the labels these tests use are the shipped English strings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(context.getString(R.string.credit_share_title), LABELS.title)
        assertEquals(context.getString(R.string.credit_share_line), LABELS.line)
        assertEquals(context.getString(R.string.credit_share_balance_owed), LABELS.balanceOwed)
        assertEquals(
            context.getString(R.string.credit_share_balance_shop_owes),
            LABELS.balanceShopOwes,
        )
        assertEquals(
            context.getString(R.string.credit_share_balance_settled),
            LABELS.balanceSettled,
        )
        assertEquals(context.getString(R.string.credit_statement_empty_title), LABELS.empty)
    }

    @Test
    fun `sharing opens a chooser for plain text and sends nothing itself`() {
        val chooser = StatementText.intent("TEXT", "Share statement")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("TEXT", send.getStringExtra(Intent.EXTRA_TEXT))
        // No recipient: the shop owner chooses where it goes.
        assertNull(send.data)
    }

    private fun build(balanceMinor: Long, vararg lines: CreditStatementLine) = StatementText.build(
        customerName = "ABEBE",
        lines = lines.toList(),
        balanceMinor = balanceMinor,
        labels = LABELS,
        // The instant is the test's day number, so the expected text stays readable.
        formatDay = { "DAY$it" },
    )

    private fun line(
        id: String,
        kind: CreditKind,
        amountMinor: Long,
        running: Long,
        day: Long,
        note: String? = null,
    ) = CreditStatementLine(
        entry = CreditEntry(
            id = id,
            customerId = "c1",
            kind = kind,
            amountMinor = amountMinor,
            occurredAt = day,
            note = note,
            linkedTransactionId = null,
        ),
        runningBalanceMinor = running,
    )

    private companion object {
        /** The English resource strings, as written in values/strings.xml. */
        val LABELS = StatementText.Labels(
            title = "Statement for {name}",
            kindGiven = "Credit given",
            kindRepayment = "Payment received",
            kindAdjustment = "Adjustment",
            line = "{day} · {kind} · {amount} · balance {balance}",
            balanceOwed = "Owed to the shop: {amount}",
            balanceShopOwes = "Owed by the shop: {amount}",
            balanceSettled = "Nothing owed.",
            empty = "Nothing yet",
        )
    }
}
