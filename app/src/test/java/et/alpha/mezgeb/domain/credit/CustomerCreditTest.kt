package et.alpha.mezgeb.domain.credit

import et.alpha.mezgeb.data.local.DAY
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.CustomerBalance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The credit book's arithmetic.
 *
 * The sign convention lives in three places — this use case, `CustomerDao` and
 * `CreditEntryDao` — because a list of customers must not load every entry to render
 * a row. That is a duplication with teeth, so the last test here runs the same
 * entries through both and asserts they agree.
 */
class CustomerCreditTest : DatabaseTest() {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val useCase = ComputeCustomerBalanceUseCase(zone)

    // --- Balance ---------------------------------------------------------------------

    @Test
    fun `credit adds, repayment subtracts, adjustment adds`() {
        val balance = useCase.balanceOf(
            listOf(
                entry("e1", CreditKind.CREDIT_GIVEN, 50_000),
                entry("e2", CreditKind.REPAYMENT, 20_000),
                entry("e3", CreditKind.ADJUSTMENT, 1_500),
            ),
        )

        assertEquals(31_500L, balance)
    }

    @Test
    fun `entry order does not change the balance`() {
        val entries = listOf(
            entry("e1", CreditKind.CREDIT_GIVEN, 50_000, at = T0 + 3 * DAY),
            entry("e2", CreditKind.REPAYMENT, 20_000, at = T0),
            entry("e3", CreditKind.CREDIT_GIVEN, 10_000, at = T0 + DAY),
        )

        // Entered out of order — a shop writes yesterday's credit up this morning.
        assertEquals(useCase.balanceOf(entries), useCase.balanceOf(entries.reversed()))
        assertEquals(40_000L, useCase.balanceOf(entries))
    }

    @Test
    fun `a repayment larger than the balance is legal and goes negative`() {
        // The shop is now holding the customer's money. Refusing this would be the app
        // telling a shop owner that something which just happened did not happen.
        val balance = useCase.balanceOf(
            listOf(
                entry("e1", CreditKind.CREDIT_GIVEN, 10_000),
                entry("e2", CreditKind.REPAYMENT, 15_000),
            ),
        )

        assertEquals(-5_000L, balance)
    }

    @Test
    fun `no entries is a zero balance, not an error`() {
        assertEquals(0L, useCase.balanceOf(emptyList()))
    }

    // --- Statement -------------------------------------------------------------------

    @Test
    fun `the running statement is ordered by date and never jumps`() {
        val lines = useCase.runningBalances(
            listOf(
                entry("e3", CreditKind.CREDIT_GIVEN, 10_000, at = T0 + 2 * DAY),
                entry("e1", CreditKind.CREDIT_GIVEN, 50_000, at = T0),
                entry("e2", CreditKind.REPAYMENT, 20_000, at = T0 + DAY),
            ),
        )

        assertEquals(listOf("e1", "e2", "e3"), lines.map { it.first.id })
        assertEquals(listOf(50_000L, 30_000L, 40_000L), lines.map { it.second })
    }

    @Test
    fun `entries sharing a timestamp are ordered by id, so the statement is stable`() {
        val lines = useCase.runningBalances(
            listOf(
                entry("b", CreditKind.REPAYMENT, 5_000, at = T0),
                entry("a", CreditKind.CREDIT_GIVEN, 20_000, at = T0),
            ),
        )

        assertEquals(listOf("a", "b"), lines.map { it.first.id })
        assertEquals(listOf(20_000L, 15_000L), lines.map { it.second })
    }

    // --- Ageing ----------------------------------------------------------------------

    @Test
    fun `days outstanding are counted in calendar days`() {
        // Credit given yesterday evening is one day old this morning, which is how the
        // person holding the book counts it.
        val summary = useCase.summarise(
            balance = balance(balanceMinor = 10_000, oldestUnpaidAt = T0),
            now = T0 + 2 * DAY,
        )

        assertEquals(2, summary.daysOutstanding)
        assertFalse(summary.isOverdue)
    }

    @Test
    fun `overdue starts at the threshold, not after it`() {
        val summary = useCase.summarise(
            balance = balance(balanceMinor = 10_000, oldestUnpaidAt = T0),
            now = T0 + 30 * DAY,
            overdueThresholdDays = 30,
        )

        assertTrue(summary.isOverdue)
    }

    @Test
    fun `a customer who owes nothing is never overdue`() {
        val summary = useCase.summarise(
            balance = balance(balanceMinor = 0, oldestUnpaidAt = T0),
            now = T0 + 90 * DAY,
        )

        assertNull(summary.daysOutstanding)
        assertFalse(summary.isOverdue)
    }

    @Test
    fun `a negative balance is the shop's debt, not an overdue one`() {
        val summary = useCase.summarise(
            balance = balance(balanceMinor = -5_000, oldestUnpaidAt = T0),
            now = T0 + 90 * DAY,
        )

        assertTrue(summary.shopOwesCustomer)
        assertFalse(summary.isOverdue)
        assertFalse(summary.owesMoney)
    }

    // --- Kotlin and SQL must agree ----------------------------------------------------

    @Test
    fun `the SQL balance matches the computed one, including a negative`() = runTest {
        val repository = CustomerRepository(db.customerDao(), db.creditEntryDao())
        db.customerDao().insert(customer("c1"))
        val entries = listOf(
            entry("e1", CreditKind.CREDIT_GIVEN, 50_000, at = T0 + 3 * DAY),
            entry("e2", CreditKind.REPAYMENT, 20_000, at = T0),
            entry("e3", CreditKind.ADJUSTMENT, 1_500, at = T0 + DAY),
            entry("e4", CreditKind.REPAYMENT, 40_000, at = T0 + 5 * DAY),
        )
        entries.forEach { entry ->
            db.creditEntryDao().insert(
                creditEntry(
                    id = entry.id,
                    customerId = "c1",
                    kind = entry.kind,
                    amountMinor = entry.amountMinor,
                    occurredAt = entry.occurredAt,
                ),
            )
        }

        val fromSql = repository.observeCustomer("c1").first()!!.balanceMinor
        val fromKotlin = useCase.balanceOf(entries)

        assertEquals(-8_500L, fromKotlin)
        assertEquals(fromKotlin, fromSql)
    }

    @Test
    fun `the SQL statement matches the computed running balances`() = runTest {
        val repository = CustomerRepository(db.customerDao(), db.creditEntryDao())
        db.customerDao().insert(customer("c1"))
        val entries = listOf(
            entry("e1", CreditKind.CREDIT_GIVEN, 50_000, at = T0),
            entry("e2", CreditKind.REPAYMENT, 20_000, at = T0 + DAY),
            entry("e3", CreditKind.CREDIT_GIVEN, 10_000, at = T0 + 2 * DAY),
        )
        entries.forEach { entry ->
            db.creditEntryDao().insert(
                creditEntry(
                    id = entry.id,
                    customerId = "c1",
                    kind = entry.kind,
                    amountMinor = entry.amountMinor,
                    occurredAt = entry.occurredAt,
                ),
            )
        }

        val fromSql = repository.observeStatement("c1").first()
        val fromKotlin = useCase.runningBalances(entries)

        assertEquals(
            fromKotlin.map { it.first.id to it.second },
            fromSql.map { it.entry.id to it.runningBalanceMinor },
        )
    }

    // --- Fixtures ----------------------------------------------------------------------

    private fun entry(
        id: String,
        kind: CreditKind,
        amountMinor: Long,
        at: Long = T0,
    ) = CreditEntry(
        id = id,
        customerId = "c1",
        kind = kind,
        amountMinor = amountMinor,
        occurredAt = at,
        note = null,
        linkedTransactionId = null,
    )

    private fun balance(balanceMinor: Long, oldestUnpaidAt: Long?) = CustomerBalance(
        customer = Customer("c1", "Abebe", null, null, T0),
        balanceMinor = balanceMinor,
        oldestUnpaidAt = oldestUnpaidAt,
    )
}
