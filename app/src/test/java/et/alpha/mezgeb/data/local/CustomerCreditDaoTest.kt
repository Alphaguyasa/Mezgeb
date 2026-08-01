package et.alpha.mezgeb.data.local

import et.alpha.mezgeb.domain.model.CreditKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Balance semantics, per the entity contract: `amountMinor` is always positive and
 * `kind` carries the sign. CREDIT_GIVEN and ADJUSTMENT increase what the customer
 * owes; REPAYMENT decreases it.
 */
class CustomerCreditDaoTest : DatabaseTest() {

    private val customers get() = db.customerDao()
    private val credit get() = db.creditEntryDao()

    @Test
    fun `a customer with no entries has a zero balance`() = runTest {
        customers.insert(customer("c1"))

        val row = customers.observeAllWithBalance().first().single()

        assertEquals(0L, row.balanceMinor)
        assertNull(row.oldestUnpaidAt)
    }

    @Test
    fun `balance mixes credit given, repayment and adjustment`() = runTest {
        customers.insert(customer("c1"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 50_000, T0),
                creditEntry("e2", "c1", CreditKind.REPAYMENT, 20_000, T0 + DAY),
                creditEntry("e3", "c1", CreditKind.ADJUSTMENT, 1_500, T0 + 2 * DAY),
            ),
        )

        // 50_000 - 20_000 + 1_500
        assertEquals(31_500L, customers.observeWithBalance("c1").first()?.balanceMinor)
    }

    @Test
    fun `a repayment exceeding the balance is legal and goes negative`() = runTest {
        // Meaning the shop now owes the customer. This is a real situation, not an error.
        customers.insert(customer("c1"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0),
                creditEntry("e2", "c1", CreditKind.REPAYMENT, 25_000, T0 + DAY),
            ),
        )

        assertEquals(-15_000L, customers.observeWithBalance("c1").first()?.balanceMinor)
    }

    @Test
    fun `out-of-order insertion does not change the final balance`() = runTest {
        customers.insert(customer("c1"))
        credit.insertAll(
            listOf(
                // Inserted newest-first, with timestamps out of order.
                creditEntry("e3", "c1", CreditKind.REPAYMENT, 5_000, T0 + 2 * DAY),
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 30_000, T0),
                creditEntry("e2", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0 + DAY),
            ),
        )

        assertEquals(35_000L, customers.observeWithBalance("c1").first()?.balanceMinor)
    }

    @Test
    fun `oldest unpaid timestamp comes from the earliest credit, not the earliest entry`() =
        runTest {
            customers.insert(customer("c1"))
            credit.insertAll(
                listOf(
                    creditEntry("e1", "c1", CreditKind.ADJUSTMENT, 100, T0),
                    creditEntry("e2", "c1", CreditKind.CREDIT_GIVEN, 30_000, T0 + 3 * DAY),
                    creditEntry("e3", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0 + 5 * DAY),
                ),
            )

            assertEquals(T0 + 3 * DAY, customers.observeWithBalance("c1").first()?.oldestUnpaidAt)
        }

    @Test
    fun `customers are listed by balance descending`() = runTest {
        customers.insert(customer("c1", name = "Small"))
        customers.insert(customer("c2", name = "Big"))
        customers.insert(customer("c3", name = "None"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 5_000, T0),
                creditEntry("e2", "c2", CreditKind.CREDIT_GIVEN, 80_000, T0),
            ),
        )

        val order = customers.observeAllWithBalance().first().map { it.name }

        assertEquals(listOf("Big", "Small", "None"), order)
    }

    @Test
    fun `total outstanding sums every customer`() = runTest {
        customers.insert(customer("c1"))
        customers.insert(customer("c2"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 5_000, T0),
                creditEntry("e2", "c2", CreditKind.CREDIT_GIVEN, 8_000, T0),
                creditEntry("e3", "c2", CreditKind.REPAYMENT, 3_000, T0 + DAY),
            ),
        )

        assertEquals(10_000L, customers.observeTotalOutstandingMinor().first())
    }

    @Test
    fun `search matches name or phone`() = runTest {
        customers.insert(customer("c1", name = "Abebe", phone = "+251900000001"))
        customers.insert(customer("c2", name = "Chaltu", phone = "+251900000002"))

        assertEquals(1, customers.searchWithBalance("Abe").first().size)
        assertEquals(1, customers.searchWithBalance("0000002").first().size)
        assertTrue(customers.searchWithBalance("zzz").first().isEmpty())
    }

    // --- Running statement ---------------------------------------------------------

    @Test
    fun `statement carries a running balance in chronological order`() = runTest {
        customers.insert(customer("c1"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 30_000, T0),
                creditEntry("e2", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0 + DAY),
                creditEntry("e3", "c1", CreditKind.REPAYMENT, 15_000, T0 + 2 * DAY),
                creditEntry("e4", "c1", CreditKind.ADJUSTMENT, 500, T0 + 3 * DAY),
            ),
        )

        val statement = credit.observeStatement("c1").first()

        assertEquals(listOf("e1", "e2", "e3", "e4"), statement.map { it.id })
        assertEquals(
            listOf(30_000L, 40_000L, 25_000L, 25_500L),
            statement.map { it.runningBalanceMinor },
        )
    }

    @Test
    fun `statement stays monotonic when two entries share a timestamp`() = runTest {
        customers.insert(customer("c1"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0),
                creditEntry("e2", "c1", CreditKind.CREDIT_GIVEN, 20_000, T0),
            ),
        )

        val statement = credit.observeStatement("c1").first()

        // Ties are broken by id, so the running balance still accumulates once per row
        // rather than showing the same total twice.
        assertEquals(listOf(10_000L, 30_000L), statement.map { it.runningBalanceMinor })
    }

    @Test
    fun `statement is scoped to one customer`() = runTest {
        customers.insert(customer("c1"))
        customers.insert(customer("c2"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0),
                creditEntry("e2", "c2", CreditKind.CREDIT_GIVEN, 99_000, T0),
            ),
        )

        val statement = credit.observeStatement("c1").first()

        assertEquals(1, statement.size)
        assertEquals(10_000L, statement.single().runningBalanceMinor)
    }

    @Test
    fun `sumByKind is bounded by the period`() = runTest {
        customers.insert(customer("c1"))
        credit.insertAll(
            listOf(
                creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0),
                creditEntry("e2", "c1", CreditKind.CREDIT_GIVEN, 20_000, T0 + 5 * DAY),
                creditEntry("e3", "c1", CreditKind.REPAYMENT, 4_000, T0 + DAY),
            ),
        )

        assertEquals(10_000L, credit.sumByKind(CreditKind.CREDIT_GIVEN, T0, T0 + 2 * DAY))
        assertEquals(4_000L, credit.sumByKind(CreditKind.REPAYMENT, T0, T0 + 2 * DAY))
        assertEquals(0L, credit.sumByKind(CreditKind.ADJUSTMENT, T0, T0 + 2 * DAY))
    }

    @Test
    fun `linking a repayment to a transaction is reversible`() = runTest {
        customers.insert(customer("c1"))
        credit.insert(creditEntry("e1", "c1", CreditKind.REPAYMENT, 4_000, T0))

        credit.setLinkedTransaction("e1", "t1")
        assertEquals("e1", credit.getByLinkedTransaction("t1")?.id)

        credit.setLinkedTransaction("e1", null)
        assertNull(credit.getByLinkedTransaction("t1"))
    }

    @Test
    fun `deleting a customer removes their credit entries`() = runTest {
        customers.insert(customer("c1"))
        credit.insert(creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 10_000, T0))

        customers.delete("c1")

        assertNull(credit.getById("e1"))
    }
}
