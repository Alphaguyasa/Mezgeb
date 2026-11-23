package et.alpha.mezgeb.domain.receipt

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receipt is read on 58 mm paper by a customer, so the tests check two things:
 * that no line is wider than the roll, and that nothing the shop keeps to itself
 * (its wallet balance, the phone number, its note) ends up on paper.
 */
class ReceiptTextTest {

    private val labels = ReceiptText.Labels(
        title = "RECEIPT",
        received = "Received",
        paid = "Paid",
        via = "Via",
        from = "From",
        to = "To",
        reference = "Ref.",
        thanks = "Thank you",
    )

    @Test
    fun `a payment received is laid out for a 58 mm roll`() {
        val text = build(transaction())

        assertEquals(
            listOf(
                "            SHOP_01",
                "================================",
                "            RECEIPT",
                "        1 Aug 2026 09:30",
                "--------------------------------",
                "Received              450.00 ETB",
                "Via: telebirr",
                "From: NAME_01",
                "Ref.: REF0001",
                "--------------------------------",
                "           Thank you",
            ),
            text.lines(),
        )
    }

    @Test
    fun `the balance, the phone number and the note stay off the paper`() {
        val text = build(
            transaction(
                counterpartyPhone = "+251900000001",
                balanceAfterMinor = 9_876_500,
                note = "NOTE_SECRET",
            ),
        )

        assertFalse(text, text.contains("+251900000001"))
        assertFalse(text, text.contains("98,765.00"))
        assertFalse(text, text.contains("NOTE_SECRET"))
    }

    @Test
    fun `money paid out names who it went to`() {
        val text = build(transaction(direction = Direction.OUT))

        assertTrue(text, text.lines().any { it.startsWith("Paid") && it.endsWith("450.00 ETB") })
        assertTrue(text, text.contains("To: NAME_01"))
    }

    @Test
    fun `no shop name and no counterparty leave those lines out, not blank`() {
        val text = build(transaction(counterpartyName = null, providerTxnId = null), shopName = "  ")

        assertEquals("            RECEIPT", text.lines().first())
        assertFalse(text, text.contains("From:"))
        assertFalse(text, text.contains("Ref."))
        assertFalse(text, text.lines().any { it.isBlank() })
    }

    @Test
    fun `no line is wider than the roll, however long the values`() {
        val text = build(
            transaction(
                amountMinor = 99_999_999_999,
                counterpartyName = "A VERY LONG COUNTERPARTY NAME THAT GOES ON AND ON",
                providerTxnId = "REF0123456789012345678901234567890123456789",
            ),
            shopName = "A SHOP WITH A NAME LONGER THAN ONE LINE OF PAPER",
        )

        text.lines().forEach { line ->
            assertTrue("'$line' is ${line.length} wide", line.length <= ReceiptText.WIDTH)
        }
        // A cut reference is still all there, only on two lines.
        assertTrue(text, text.replace("\n", "").contains("REF0123456789012345678901234567890123456789"))
        assertTrue(text, text.contains("999,999,999.99 ETB"))
    }

    @Test
    fun `an amount too wide to share a line with its label gets its own`() {
        assertEquals(
            "ABCDEFGHIJKLMNOPQRSTUVWX\n" + "100.00 ETB".padStart(ReceiptText.WIDTH),
            ReceiptText.spread("ABCDEFGHIJKLMNOPQRSTUVWX", "100.00 ETB"),
        )
    }

    private fun build(transaction: Transaction, shopName: String? = "SHOP_01") = ReceiptText.build(
        transaction = transaction,
        shopName = shopName,
        channelLabel = "telebirr",
        formattedWhen = "1 Aug 2026 09:30",
        labels = labels,
    )

    private fun transaction(
        direction: Direction = Direction.IN,
        amountMinor: Long = 45_000,
        counterpartyName: String? = "NAME_01",
        counterpartyPhone: String? = null,
        providerTxnId: String? = "REF0001",
        balanceAfterMinor: Long? = null,
        note: String? = null,
    ) = Transaction(
        id = "t1",
        rawMessageId = null,
        direction = direction,
        amountMinor = amountMinor,
        currency = "ETB",
        channel = Channel.TELEBIRR,
        counterpartyName = counterpartyName,
        counterpartyPhone = counterpartyPhone,
        providerTxnId = providerTxnId,
        balanceAfterMinor = balanceAfterMinor,
        occurredAt = 0L,
        categoryId = null,
        note = note,
        isReconciled = false,
        reconciliationSessionId = null,
        isDeleted = false,
        editedAt = null,
    )
}
