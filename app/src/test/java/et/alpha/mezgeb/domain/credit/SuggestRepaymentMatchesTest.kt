package et.alpha.mezgeb.domain.credit

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestRepaymentMatchesTest {

    private val useCase = SuggestRepaymentMatchesUseCase()

    private val customer = Customer(
        id = "c1",
        name = "Abebe",
        phone = "+251911234567",
        note = null,
        createdAt = 0L,
    )

    @Test
    fun `the payment for the same amount comes first`() {
        val ranked = useCase.rank(
            candidates = listOf(
                transaction("t1", amountMinor = 10_000, phone = null),
                transaction("t2", amountMinor = 45_000, phone = null),
            ),
            customer = customer,
            amountMinor = 45_000,
        )

        assertEquals("t2", ranked.first().transaction.id)
        assertTrue(ranked.first().matchesAmount)
    }

    @Test
    fun `amount beats phone, because amount is the stronger signal`() {
        val ranked = useCase.rank(
            candidates = listOf(
                transaction("phone_only", amountMinor = 10_000, phone = "+251911234567"),
                transaction("amount_only", amountMinor = 45_000, phone = null),
            ),
            customer = customer,
            amountMinor = 45_000,
        )

        assertEquals(listOf("amount_only", "phone_only"), ranked.map { it.transaction.id })
    }

    @Test
    fun `both beats either`() {
        val ranked = useCase.rank(
            candidates = listOf(
                transaction("amount_only", amountMinor = 45_000, phone = null),
                transaction("both", amountMinor = 45_000, phone = "+251911234567"),
            ),
            customer = customer,
            amountMinor = 45_000,
        )

        assertEquals("both", ranked.first().transaction.id)
        assertTrue(ranked.first().matchesPhone)
    }

    @Test
    fun `the same number written three ways is one number`() {
        // Which form arrives depends on the provider that sent the message.
        listOf("+251911234567", "0911234567", "911234567").forEach { stored ->
            val ranked = useCase.rank(
                candidates = listOf(transaction("t1", amountMinor = 1, phone = stored)),
                customer = customer,
                amountMinor = 99_999,
            )
            assertTrue("$stored should match the customer's number", ranked.single().matchesPhone)
        }
    }

    @Test
    fun `a different number does not match`() {
        val ranked = useCase.rank(
            candidates = listOf(transaction("t1", amountMinor = 1, phone = "+251922222222")),
            customer = customer,
            amountMinor = 99_999,
        )

        assertFalse(ranked.single().matchesPhone)
    }

    @Test
    fun `equally good candidates fall back to the most recent`() {
        val ranked = useCase.rank(
            candidates = listOf(
                transaction("older", amountMinor = 45_000, phone = null, occurredAt = 1_000),
                transaction("newer", amountMinor = 45_000, phone = null, occurredAt = 9_000),
            ),
            customer = customer,
            amountMinor = 45_000,
        )

        assertEquals(listOf("newer", "older"), ranked.map { it.transaction.id })
    }

    @Test
    fun `candidates that match nothing are still offered, just last`() {
        // The shop may know something the app does not. Suggesting an order is the
        // job; deciding is not.
        val ranked = useCase.rank(
            candidates = listOf(
                transaction("unrelated", amountMinor = 700, phone = null),
                transaction("exact", amountMinor = 45_000, phone = null),
            ),
            customer = customer,
            amountMinor = 45_000,
        )

        assertEquals(2, ranked.size)
        assertEquals("unrelated", ranked.last().transaction.id)
        assertEquals(0, ranked.last().score)
    }

    @Test
    fun `a customer with no phone still gets amount matches`() {
        val ranked = useCase.rank(
            candidates = listOf(transaction("t1", amountMinor = 45_000, phone = "+251911234567")),
            customer = customer.copy(phone = null),
            amountMinor = 45_000,
        )

        assertTrue(ranked.single().matchesAmount)
        assertFalse(ranked.single().matchesPhone)
    }

    private fun transaction(
        id: String,
        amountMinor: Long,
        phone: String?,
        occurredAt: Long = 1_000L,
    ) = Transaction(
        id = id,
        rawMessageId = null,
        direction = Direction.IN,
        amountMinor = amountMinor,
        currency = "ETB",
        channel = Channel.TELEBIRR,
        counterpartyName = null,
        counterpartyPhone = phone,
        providerTxnId = null,
        balanceAfterMinor = null,
        occurredAt = occurredAt,
        categoryId = null,
        note = null,
        isReconciled = false,
        reconciliationSessionId = null,
        isDeleted = false,
        editedAt = null,
    )
}
