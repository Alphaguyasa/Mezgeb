package et.alpha.mezgeb.data.local

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ledger's filter query is the one place in the persistence layer where a subtle
 * mistake would be invisible in the UI — a wrong row silently missing from a list. So
 * every filter is exercised alone, and then in combination.
 */
class TransactionDaoTest : DatabaseTest() {

    private val dao get() = db.transactionDao()

    /** Seven transactions spread across channels, directions, categories and days. */
    @Before
    fun seed() = runTest {
        db.categoryDao().insertIgnoringExisting(
            listOf(category("cat_sales"), category("cat_rent")),
        )
        dao.insertAll(
            listOf(
                transaction("t1", direction = Direction.IN, amountMinor = 45_000, channel = Channel.TELEBIRR, occurredAt = T0, categoryId = "cat_sales", counterpartyName = "Abebe", providerTxnId = "AAA1BBB2CC"),
                transaction("t2", direction = Direction.IN, amountMinor = 120_000, channel = Channel.CBE_BIRR, occurredAt = T0 + DAY, categoryId = "cat_sales", counterpartyName = "Chaltu", counterpartyPhone = "+251900000002"),
                transaction("t3", direction = Direction.OUT, amountMinor = 30_000, channel = Channel.BANK, occurredAt = T0 + 2 * DAY, categoryId = "cat_rent", counterpartyName = "Landlord"),
                transaction("t4", direction = Direction.IN, amountMinor = 5_000, channel = Channel.CASH, occurredAt = T0 + 3 * DAY, note = "walk-in sale"),
                transaction("t5", direction = Direction.OUT, amountMinor = 8_050, channel = Channel.TELEBIRR, occurredAt = T0 + 4 * DAY, isReconciled = true),
                transaction("t6", direction = Direction.IN, amountMinor = 99_999, channel = Channel.TELEBIRR, occurredAt = T0 + 5 * DAY, isDeleted = true),
                transaction("t7", direction = Direction.IN, amountMinor = 1_000, channel = Channel.OTHER, occurredAt = T0 + 6 * DAY, isReconciled = true, categoryId = "cat_sales"),
            ),
        )
    }

    private suspend fun ids(
        from: Long? = null,
        to: Long? = null,
        channel: Channel? = null,
        direction: Direction? = null,
        categoryId: String? = null,
        isReconciled: Boolean? = null,
        query: String? = null,
    ) = dao.listFiltered(from, to, channel, direction, categoryId, isReconciled, query).map { it.id }

    @Test
    fun `no filters returns everything except soft-deleted rows, newest first`() = runTest {
        assertEquals(listOf("t7", "t5", "t4", "t3", "t2", "t1"), ids())
    }

    @Test
    fun `filter by date range is inclusive at both ends`() = runTest {
        assertEquals(listOf("t3", "t2", "t1"), ids(from = T0, to = T0 + 2 * DAY))
    }

    @Test
    fun `filter by open-ended date range`() = runTest {
        assertEquals(listOf("t7", "t5"), ids(from = T0 + 4 * DAY))
        assertEquals(listOf("t2", "t1"), ids(to = T0 + DAY))
    }

    @Test
    fun `filter by channel`() = runTest {
        assertEquals(listOf("t5", "t1"), ids(channel = Channel.TELEBIRR))
        assertEquals(listOf("t4"), ids(channel = Channel.CASH))
    }

    @Test
    fun `filter by direction`() = runTest {
        assertEquals(listOf("t7", "t4", "t2", "t1"), ids(direction = Direction.IN))
        assertEquals(listOf("t5", "t3"), ids(direction = Direction.OUT))
    }

    @Test
    fun `filter by category`() = runTest {
        assertEquals(listOf("t7", "t2", "t1"), ids(categoryId = "cat_sales"))
        assertEquals(listOf("t3"), ids(categoryId = "cat_rent"))
    }

    @Test
    fun `filter by reconciled flag`() = runTest {
        assertEquals(listOf("t7", "t5"), ids(isReconciled = true))
        assertEquals(listOf("t4", "t3", "t2", "t1"), ids(isReconciled = false))
    }

    @Test
    fun `search matches name, phone, reference and note`() = runTest {
        assertEquals(listOf("t1"), ids(query = "Abebe"))
        assertEquals(listOf("t2"), ids(query = "0000002"))
        assertEquals(listOf("t1"), ids(query = "AAA1BBB"))
        assertEquals(listOf("t4"), ids(query = "walk-in"))
    }

    @Test
    fun `search returns nothing when there is no match`() = runTest {
        assertTrue(ids(query = "no-such-thing").isEmpty())
    }

    @Test
    fun `filters combine conjunctively`() = runTest {
        // IN + telebirr + within the first two days => t1 only.
        assertEquals(
            listOf("t1"),
            ids(
                from = T0,
                to = T0 + 2 * DAY,
                channel = Channel.TELEBIRR,
                direction = Direction.IN,
            ),
        )

        // Sales category, IN, not reconciled => t1 and t2, but not t7 which is reconciled.
        assertEquals(
            listOf("t2", "t1"),
            ids(categoryId = "cat_sales", direction = Direction.IN, isReconciled = false),
        )
    }

    @Test
    fun `combining filters that share no rows returns empty`() = runTest {
        assertTrue(ids(channel = Channel.CASH, direction = Direction.OUT).isEmpty())
    }

    @Test
    fun `soft-deleted rows never appear regardless of filters`() = runTest {
        assertTrue(ids(channel = Channel.TELEBIRR, query = "99999").isEmpty())
        assertTrue(ids().none { it == "t6" })
    }

    // --- Aggregates ---------------------------------------------------------------

    @Test
    fun `sums by direction exclude soft-deleted rows`() = runTest {
        val from = T0
        val to = T0 + 10 * DAY

        // t1 + t2 + t4 + t7 = 45_000 + 120_000 + 5_000 + 1_000. t6 is deleted.
        assertEquals(171_000L, dao.sumByDirection(Direction.IN, from, to))
        assertEquals(38_050L, dao.sumByDirection(Direction.OUT, from, to))
    }

    @Test
    fun `sum over an empty period is zero rather than null`() = runTest {
        assertEquals(0L, dao.sumByDirection(Direction.IN, T0 + 100 * DAY, T0 + 200 * DAY))
    }

    @Test
    fun `cash recorded is cash in only`() = runTest {
        assertEquals(
            5_000L,
            dao.sumByChannelAndDirection(Channel.CASH, Direction.IN, T0, T0 + 10 * DAY),
        )
    }

    @Test
    fun `totals by category group and order by size`() = runTest {
        val totals = dao.totalsByCategory(Direction.IN, T0, T0 + 10 * DAY)

        assertEquals("cat_sales", totals[0].categoryId)
        assertEquals(166_000L, totals[0].totalMinor)
        assertEquals(3, totals[0].transactionCount)
        // Uncategorised money still has to be visible, as a null bucket.
        assertEquals(null, totals[1].categoryId)
        assertEquals(5_000L, totals[1].totalMinor)
    }

    @Test
    fun `totals by channel group correctly`() = runTest {
        val byChannel = dao.totalsByChannel(Direction.IN, T0, T0 + 10 * DAY)
            .associate { it.channel to it.totalMinor }

        assertEquals(120_000L, byChannel[Channel.CBE_BIRR])
        assertEquals(45_000L, byChannel[Channel.TELEBIRR])
        assertEquals(5_000L, byChannel[Channel.CASH])
        assertEquals(1_000L, byChannel[Channel.OTHER])
    }

    @Test
    fun `countUncategorised counts only rows with no category`() = runTest {
        assertEquals(2, dao.countUncategorised(T0, T0 + 10 * DAY))
    }

    // --- Mutations ----------------------------------------------------------------

    @Test
    fun `soft delete hides the row but keeps it retrievable by id`() = runTest {
        dao.softDelete("t1", now = T0 + 9 * DAY)

        assertTrue(ids().none { it == "t1" })
        val stored = dao.getById("t1")
        assertEquals(true, stored?.isDeleted)
        assertEquals(T0 + 9 * DAY, stored?.touchedAt)
        assertNull("deleting changes no parsed value", stored?.editedAt)
    }

    @Test
    fun `restore brings a soft-deleted row back without marking it edited`() = runTest {
        dao.restore("t6", now = T0 + 9 * DAY)

        assertTrue(ids().contains("t6"))
        val stored = dao.getById("t6")
        assertEquals(T0 + 9 * DAY, stored?.touchedAt)
        // A row that goes away and comes back says exactly what it always said.
        assertNull("restoring changes no parsed value", stored?.editedAt)
    }

    @Test
    fun `filing under a category is a touch, not an edit`() = runTest {
        dao.setCategory("t4", "cat_sales", now = T0 + 9 * DAY)

        val stored = dao.getById("t4")
        assertEquals("cat_sales", stored?.categoryId)
        assertEquals(T0 + 9 * DAY, stored?.touchedAt)
        // The bug this split exists for: the ledger showed an "edited" marker on a
        // transaction whose every parsed value still matched its message.
        assertNull("categorising must not mark a row as edited", stored?.editedAt)
        // Reconciliation still sees it, because the figures did move.
        assertEquals(1, dao.countTouchedSince(T0, T0, T0 + 10 * DAY))
    }

    @Test
    fun `countTouchedSince ignores touches made before the cutoff`() = runTest {
        dao.setCategory("t4", "cat_sales", now = T0 + DAY)
        assertEquals(0, dao.countTouchedSince(T0 + 5 * DAY, T0, T0 + 10 * DAY))
    }

    @Test
    fun `deleting a raw message nulls the link but keeps the transaction`() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(rawMessage("raw1"))
        dao.insert(transaction("t8", rawMessageId = "raw1", occurredAt = T0 + 7 * DAY))

        db.rawMessageDao().deleteAll()

        val stored = dao.getById("t8")
        assertNull("the foreign key should SET NULL, not cascade", stored?.rawMessageId)
        assertEquals("t8", stored?.id)
    }

    @Test
    fun `observeRecent respects the limit and ordering`() = runTest {
        assertEquals(listOf("t7", "t5", "t4"), dao.observeRecent(3).first().map { it.id })
    }
}
