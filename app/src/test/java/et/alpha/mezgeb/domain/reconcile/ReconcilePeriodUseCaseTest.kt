package et.alpha.mezgeb.domain.reconcile

import et.alpha.mezgeb.data.local.DAY
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.CustomerRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParseState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The reconciliation arithmetic, against a real database.
 *
 * The first test is the one that matters most. Implemented exactly as
 * 03-ARCHITECTURE.md section 7 writes it, every hand-entered cash sale is counted
 * twice and the shop is told it is short by precisely the cash it took — the most
 * damaging answer a reconciliation screen can give, because it sends someone looking
 * for money that is not missing.
 */
class ReconcilePeriodUseCaseTest : DatabaseTest() {

    private val periodStart = T0
    private val periodEnd = T0 + DAY - 1
    private val openedAt = T0 + 12 * HOUR

    private lateinit var transactions: TransactionRepository
    private lateinit var customers: CustomerRepository
    private var expected: Long? = null

    @Before
    fun setUp() {
        transactions = TransactionRepository(db.transactionDao(), db.rawMessageDao())
        customers = CustomerRepository(db.customerDao(), db.creditEntryDao())
    }

    private fun useCase() = ReconcilePeriodUseCase(
        transactions = transactions,
        customers = customers,
        expectedSales = object : ExpectedSalesProvider {
            override suspend fun expectedTotalMinor(periodStart: Long, periodEnd: Long) = expected
        },
    )

    @Test
    fun `a cash sale is counted once, not twice`() = runTest {
        // 200 birr through telebirr, 100 in cash. Accounted for is 300, not 400.
        db.transactionDao().insertAll(
            listOf(
                transaction("t1", channel = Channel.TELEBIRR, direction = Direction.IN, amountMinor = 20_000, occurredAt = T0 + HOUR),
                transaction("t2", channel = Channel.CASH, direction = Direction.IN, amountMinor = 10_000, occurredAt = T0 + HOUR),
            ),
        )
        expected = 30_000

        val result = useCase()(periodStart, periodEnd, openedAt)

        assertEquals(20_000L, result.capturedInMinor)
        assertEquals(10_000L, result.cashRecordedMinor)
        assertEquals(30_000L, result.accountedForMinor)
        assertEquals(0L, result.gapMinor)
        assertTrue("a balanced day must read as balanced", result.isBalanced)
    }

    @Test
    fun `the full formula`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("in", channel = Channel.TELEBIRR, direction = Direction.IN, amountMinor = 50_000, occurredAt = T0 + HOUR),
                transaction("cash", channel = Channel.CASH, direction = Direction.IN, amountMinor = 20_000, occurredAt = T0 + HOUR),
                transaction("out", channel = Channel.BANK, direction = Direction.OUT, amountMinor = 8_000, occurredAt = T0 + HOUR),
            ),
        )
        db.customerDao().insert(customer("c1"))
        db.creditEntryDao().insert(
            creditEntry("given", "c1", CreditKind.CREDIT_GIVEN, 15_000, occurredAt = T0 + HOUR),
        )
        db.creditEntryDao().insert(
            creditEntry("repaid", "c1", CreditKind.REPAYMENT, 5_000, occurredAt = T0 + HOUR),
        )
        expected = 90_000

        val result = useCase()(periodStart, periodEnd, openedAt)

        // 50,000 + 20,000 + 15,000 − 5,000
        assertEquals(80_000L, result.accountedForMinor)
        assertEquals(8_000L, result.capturedOutMinor)
        assertEquals(10_000L, result.gapMinor)
        assertTrue(result.isShort)
    }

    @Test
    fun `without an expected total there is no gap to state`() = runTest {
        db.transactionDao().insert(transaction("t1", amountMinor = 50_000, occurredAt = T0 + HOUR))
        expected = null

        val result = useCase()(periodStart, periodEnd, openedAt)

        // Not zero: nobody has said what they expected, so no claim can be made.
        assertNull(result.gapMinor)
        assertFalse(result.isBalanced)
    }

    @Test
    fun `transactions outside the period are none of its business`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("inside", amountMinor = 50_000, occurredAt = T0 + HOUR),
                transaction("before", amountMinor = 99_000, occurredAt = T0 - HOUR),
                transaction("after", amountMinor = 99_000, occurredAt = periodEnd + 1),
            ),
        )

        assertEquals(50_000L, useCase()(periodStart, periodEnd, openedAt).capturedInMinor)
    }

    @Test
    fun `a deleted transaction does not count`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("kept", amountMinor = 50_000, occurredAt = T0 + HOUR),
                transaction("gone", amountMinor = 90_000, occurredAt = T0 + HOUR, isDeleted = true),
            ),
        )

        assertEquals(50_000L, useCase()(periodStart, periodEnd, openedAt).capturedInMinor)
    }

    // --- Explanations ------------------------------------------------------------------

    @Test
    fun `explanations are ranked by how much of the gap they could account for`() = runTest {
        // Unreadable messages worth roughly 450 birr.
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(
                "m1",
                body = "You have received ETB 450.00 from ABEBE",
                contentHash = "m1",
                receivedAt = T0 + HOUR,
                parseState = ParseState.UNMATCHED,
            ),
        )
        // An uncategorised transaction worth 120 birr.
        db.transactionDao().insert(
            transaction("t1", amountMinor = 12_000, categoryId = null, occurredAt = T0 + HOUR),
        )
        // Outstanding credit of 900 birr.
        db.customerDao().insert(customer("c1"))
        db.creditEntryDao().insert(
            creditEntry("given", "c1", CreditKind.CREDIT_GIVEN, 90_000, occurredAt = T0 + HOUR),
        )

        val explanations = useCase()(periodStart, periodEnd, openedAt).explanations

        assertEquals(
            listOf(90_000L, 45_000L, 12_000L),
            explanations.map { it.potentialMinor },
        )
    }

    @Test
    fun `an explanation with no knowable amount ranks last, not as zero`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("t1", amountMinor = 12_000, categoryId = null, occurredAt = T0 + HOUR),
                transaction(
                    "t2",
                    amountMinor = 5_000,
                    categoryId = "cat_sales",
                    occurredAt = T0 + HOUR,
                    touchedAt = openedAt + HOUR,
                ),
            ),
        )

        val explanations = useCase()(periodStart, periodEnd, openedAt).explanations

        assertTrue(explanations.first() is GapExplanation.UncategorizedTransactions)
        assertTrue(explanations.last() is GapExplanation.EditedSincePeriodOpened)
    }

    @Test
    fun `an edit made before the period was opened is not news`() = runTest {
        db.transactionDao().insert(
            transaction(
                "t1",
                amountMinor = 5_000,
                categoryId = "cat_sales",
                occurredAt = T0 + HOUR,
                touchedAt = openedAt - HOUR,
            ),
        )

        val explanations = useCase()(periodStart, periodEnd, openedAt).explanations

        assertTrue(explanations.none { it is GapExplanation.EditedSincePeriodOpened })
    }

    @Test
    fun `filing a transaction inside an open period still explains the gap`() = runTest {
        db.transactionDao().insert(
            transaction("t1", amountMinor = 5_000, categoryId = null, occurredAt = T0 + HOUR),
        )

        // The narrower editedAt would miss this entirely, and a reconciliation that
        // under-explains a gap sends a shop owner counting notes for no reason.
        db.transactionDao().setCategory("t1", "cat_sales", now = openedAt + HOUR)

        val explanations = useCase()(periodStart, periodEnd, openedAt).explanations

        assertTrue(explanations.any { it is GapExplanation.EditedSincePeriodOpened })
        assertNull("and it is still not an edit", db.transactionDao().getById("t1")?.editedAt)
    }

    @Test
    fun `unreadable messages report how many carried a readable amount`() = runTest {
        db.rawMessageDao().insertAllIgnoringDuplicates(
            listOf(
                rawMessage("m1", body = "You have received ETB 450.00", contentHash = "m1", receivedAt = T0 + HOUR, parseState = ParseState.UNMATCHED),
                rawMessage("m2", body = "Beware of fraud", contentHash = "m2", receivedAt = T0 + HOUR, parseState = ParseState.UNMATCHED),
            ),
        )

        val explanation = useCase()(periodStart, periodEnd, openedAt).explanations
            .filterIsInstance<GapExplanation.UnmatchedMessages>()
            .single()

        assertEquals(2, explanation.count)
        // Two messages, one amount: the partial total is honest about being partial.
        assertEquals(1, explanation.amountsFound)
        assertEquals(45_000L, explanation.partialTotalMinor)
    }

    @Test
    fun `a possible duplicate is named with its amount and never subtracted`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("first", amountMinor = 45_000, categoryId = "cat_sales", occurredAt = T0 + HOUR),
                transaction(
                    "second",
                    amountMinor = 45_000,
                    categoryId = "cat_sales",
                    occurredAt = T0 + HOUR + 30_000,
                    possibleDuplicateOfId = "first",
                ),
            ),
        )
        expected = 45_000

        val result = useCase()(periodStart, periodEnd, openedAt)

        // Both copies still count: it may be a real second payment, and only the shop
        // knows. Reconciliation must never decide that for them.
        assertEquals(90_000L, result.capturedInMinor)
        assertEquals(-45_000L, result.gapMinor)
        assertEquals(
            GapExplanation.PossibleDuplicates(count = 1, totalMinor = 45_000),
            result.explanations.single(),
        )
    }

    @Test
    fun `a dismissed or deleted possible duplicate is no longer named`() = runTest {
        db.transactionDao().insertAll(
            listOf(
                transaction("first", amountMinor = 45_000, categoryId = "cat_sales", occurredAt = T0 + HOUR),
                transaction("kept", amountMinor = 45_000, categoryId = "cat_sales", occurredAt = T0 + HOUR, possibleDuplicateOfId = "first"),
                transaction("deleted", amountMinor = 45_000, categoryId = "cat_sales", occurredAt = T0 + HOUR, possibleDuplicateOfId = "first", isDeleted = true),
            ),
        )
        db.transactionDao().clearPossibleDuplicate("kept")

        val explanations = useCase()(periodStart, periodEnd, openedAt).explanations

        assertTrue(explanations.none { it is GapExplanation.PossibleDuplicates })
    }

    @Test
    fun `nothing wrong means nothing to explain`() = runTest {
        db.transactionDao().insert(
            transaction("t1", amountMinor = 50_000, categoryId = "cat_sales", occurredAt = T0 + HOUR),
        )

        assertTrue(useCase()(periodStart, periodEnd, openedAt).explanations.isEmpty())
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
