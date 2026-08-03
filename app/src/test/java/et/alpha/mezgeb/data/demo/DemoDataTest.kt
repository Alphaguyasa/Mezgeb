package et.alpha.mezgeb.data.demo

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import et.alpha.mezgeb.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * The demo is the first thing a stranger sees, so its shape is asserted rather than
 * eyeballed. These are the claims the session brief makes about it.
 */
class DemoDataTest {

    private val zone: ZoneId = ZoneId.of("Africa/Addis_Ababa")

    /** A Wednesday, so the day-of-week weighting is exercised from a normal day. */
    private val now: Long = Instant.parse("2026-08-05T19:30:00Z").toEpochMilli()

    private val shop = DemoData.build(now, zone)

    @Test
    fun `has roughly two hundred transactions across ninety days`() {
        assertTrue(
            "expected ~200 transactions, got ${shop.transactions.size}",
            shop.transactions.size in 170..260,
        )
    }

    @Test
    fun `history spans ninety days and reaches today`() {
        val days = shop.transactions
            .map { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        assertEquals(today, days.max())
        assertTrue("history is shorter than 90 days", days.min() <= today.minusDays(85))
    }

    @Test
    fun `nothing is dated in the future`() {
        assertTrue(shop.transactions.all { it.occurredAt <= now })
        assertTrue(shop.rawMessages.all { it.receivedAt <= now })
    }

    @Test
    fun `saturday takes more than sunday`() {
        val byDay = shop.transactions
            .filter { it.direction == Direction.IN }
            .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).dayOfWeek }
            .mapValues { (_, txns) -> txns.sumOf { it.amountMinor } }

        val saturday = byDay[DayOfWeek.SATURDAY] ?: 0L
        val sunday = byDay[DayOfWeek.SUNDAY] ?: 0L
        assertTrue("saturday $saturday should beat sunday $sunday", saturday > sunday * 2)
    }

    @Test
    fun `amounts have a long tail rather than a uniform spread`() {
        val amounts = shop.transactions
            .filter { it.direction == Direction.IN }
            .map { it.amountMinor }
            .sorted()

        val median = amounts[amounts.size / 2]
        // A uniform distribution puts the mean on the median. A real shop's takings
        // put it well above, because a handful of large sales drag it up.
        assertTrue("mean should exceed median", amounts.average() > median * 1.2)
        assertTrue("largest should dwarf the median", amounts.last() > median * 5)
    }

    @Test
    fun `round numbers are over-represented, as when people pay in notes`() {
        val incoming = shop.transactions.filter { it.direction == Direction.IN }
        val round = incoming.count { it.amountMinor % 5_000L == 0L }
        assertTrue(
            "only $round of ${incoming.size} amounts were a round 50 birr",
            round > incoming.size / 3,
        )
    }

    @Test
    fun `there are cash sales with no message and captured sales with one`() {
        val cash = shop.transactions.filter { it.channel == Channel.CASH }
        assertTrue("no cash sales at all", cash.isNotEmpty())
        assertTrue("cash should never carry a message", cash.all { it.rawMessageId == null })

        val captured = shop.transactions.filter { it.rawMessageId != null }
        assertTrue("no captured sales at all", captured.isNotEmpty())
    }

    @Test
    fun `every linked message exists and every reference agrees`() {
        val byId = shop.rawMessages.associateBy { it.id }
        shop.transactions.forEach { txn ->
            val messageId = txn.rawMessageId ?: return@forEach
            val message = byId[messageId]
            assertNotNull("dangling rawMessageId $messageId", message)
            assertNotNull("captured transaction with no reference", txn.providerTxnId)
            assertTrue(
                "reference ${txn.providerTxnId} missing from its own message",
                message!!.body.contains(txn.providerTxnId!!),
            )
        }
    }

    @Test
    fun `content hashes are unique, as the schema requires`() {
        val hashes = shop.rawMessages.map { it.contentHash }
        assertEquals(hashes.size, hashes.toSet().size)
    }

    @Test
    fun `outgoings include rent and are far fewer than takings`() {
        val out = shop.transactions.filter { it.direction == Direction.OUT }
        val income = shop.transactions.count { it.direction == Direction.IN }

        assertTrue("no outgoings", out.isNotEmpty())
        assertTrue("too many outgoings for a shop", out.size < income / 4)
        assertTrue("no rent paid in three months", out.any { it.categoryId == "cat_rent" })
    }

    // --- Review queue -----------------------------------------------------------------

    @Test
    fun `six messages wait in the review queue in two formats`() {
        val queued = shop.rawMessages.filter { it.parseState == ParseState.UNMATCHED }
        assertEquals(6, queued.size)
        assertEquals(2, queued.map { it.sender }.toSet().size)
    }

    // --- Credit book ------------------------------------------------------------------

    @Test
    fun `twelve customers, two of them overdue`() {
        assertEquals(12, shop.customers.size)

        val thirtyDaysAgo = now - 30L * 24 * 3_600_000
        val overdue = shop.customers.count { customer ->
            val entries = shop.creditEntries.filter { it.customerId == customer.id }
            val balance = entries.sumOf { entry ->
                when (entry.kind) {
                    CreditKind.CREDIT_GIVEN -> entry.amountMinor
                    CreditKind.REPAYMENT -> -entry.amountMinor
                    CreditKind.ADJUSTMENT -> entry.amountMinor
                }
            }
            val oldestUnpaid = entries
                .filter { it.kind == CreditKind.CREDIT_GIVEN }
                .minOfOrNull { it.occurredAt } ?: Long.MAX_VALUE
            balance > 0 && oldestUnpaid < thirtyDaysAgo
        }
        assertEquals(2, overdue)
    }

    @Test
    fun `credit balances vary, including some settled to zero`() {
        val balances = shop.customers.map { customer ->
            shop.creditEntries.filter { it.customerId == customer.id }.sumOf { entry ->
                if (entry.kind == CreditKind.REPAYMENT) -entry.amountMinor else entry.amountMinor
            }
        }
        assertTrue("no customer is settled up", balances.any { it == 0L })
        assertTrue("no customer owes anything", balances.any { it > 0L })
        assertTrue("a customer is in credit, which the demo should not show", balances.all { it >= 0L })
    }

    @Test
    fun `every credit entry belongs to a customer that exists`() {
        val ids = shop.customers.map { it.id }.toSet()
        assertTrue(shop.creditEntries.all { it.customerId in ids })
    }

    // --- Reconciliation ---------------------------------------------------------------

    @Test
    fun `one closed period and one open one`() {
        assertEquals(2, shop.sessions.size)
        assertEquals(1, shop.sessions.count { it.status == SessionStatus.CLOSED })
        assertEquals(1, shop.sessions.count { it.status == SessionStatus.OPEN })
    }

    @Test
    fun `the closed period's figures match the transactions inside it`() {
        val closed = shop.sessions.first { it.status == SessionStatus.CLOSED }
        val inside = shop.transactions.filter { it.occurredAt in closed.periodStart..closed.periodEnd }

        assertEquals(
            inside.filter { it.direction == Direction.IN && it.channel != Channel.CASH }
                .sumOf { it.amountMinor },
            closed.capturedInMinor,
        )
        assertEquals(
            inside.filter { it.direction == Direction.OUT }.sumOf { it.amountMinor },
            closed.capturedOutMinor,
        )
        assertEquals(
            inside.filter { it.direction == Direction.IN && it.channel == Channel.CASH }
                .sumOf { it.amountMinor },
            closed.cashRecordedMinor,
        )
        assertNotNull("a closed period should have been counted", closed.expectedTotalMinor)
        assertNotNull(closed.closedAt)
    }

    @Test
    fun `the closed period is short by a visible amount, so the gap has something to explain`() {
        val closed = shop.sessions.first { it.status == SessionStatus.CLOSED }
        val gap = closed.expectedTotalMinor!! - (closed.capturedInMinor + closed.cashRecordedMinor)
        assertTrue("the gap should be a real shortfall, was $gap", gap < 0)
    }

    @Test
    fun `the open period has no closing figures`() {
        val open = shop.sessions.first { it.status == SessionStatus.OPEN }
        assertNull(open.closedAt)
        assertNull(open.expectedTotalMinor)
        assertEquals(0L, open.capturedInMinor)
    }

    // --- Reproducibility --------------------------------------------------------------

    @Test
    fun `the same moment produces the same shop twice`() {
        val again = DemoData.build(now, zone)
        assertEquals(shop.transactions, again.transactions)
        assertEquals(shop.rawMessages, again.rawMessages)
        assertEquals(shop.creditEntries, again.creditEntries)
    }
}
