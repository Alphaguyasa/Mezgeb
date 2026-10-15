package et.alpha.mezgeb.data.local

import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The fallback dedup lookup. Every condition in its WHERE clause is a reason two rows
 * are *not* the same payment, so each one gets a test that it is actually applied.
 */
class TransactionLookalikeTest : DatabaseTest() {

    private val dao get() = db.transactionDao()

    @Before
    fun seed() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(rawMessage("m1"))
        dao.insert(
            transaction(
                "t1",
                rawMessageId = "m1",
                channel = Channel.BANK,
                amountMinor = 45_000,
                balanceAfterMinor = 120_000,
                occurredAt = T0,
            ),
        )
    }

    private suspend fun lookalike(
        channel: Channel = Channel.BANK,
        direction: Direction = Direction.IN,
        amountMinor: Long = 45_000,
        balanceAfterMinor: Long? = 120_000,
        occurredAt: Long = T0 + 30_000,
        providerTxnId: String? = null,
    ) = dao.findLookalike(
        channel = channel,
        direction = direction,
        amountMinor = amountMinor,
        balanceAfterMinor = balanceAfterMinor,
        occurredAt = occurredAt,
        providerTxnId = providerTxnId,
        windowMillis = 60_000,
    )?.id

    @Test
    fun `the same payment inside the window is found`() = runTest {
        assertEquals("t1", lookalike())
        // Either side of it, and inclusive at the edge.
        assertEquals("t1", lookalike(occurredAt = T0 - 60_000))
        assertEquals("t1", lookalike(occurredAt = T0 + 60_000))
    }

    @Test
    fun `outside the window is a different payment`() = runTest {
        assertNull(lookalike(occurredAt = T0 + 60_001))
        assertNull(lookalike(occurredAt = T0 - 60_001))
    }

    @Test
    fun `channel, direction, amount and balance must all agree`() = runTest {
        assertNull(lookalike(channel = Channel.TELEBIRR))
        assertNull(lookalike(direction = Direction.OUT))
        assertNull(lookalike(amountMinor = 45_001))
        assertNull(lookalike(balanceAfterMinor = 120_001))
        // A message that states no balance is not the same as one that does.
        assertNull(lookalike(balanceAfterMinor = null))
    }

    @Test
    fun `two messages that both lack a balance still compare`() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(rawMessage("m2"))
        dao.insert(
            transaction(
                "t2",
                rawMessageId = "m2",
                channel = Channel.CBE_BIRR,
                balanceAfterMinor = null,
                occurredAt = T0,
            ),
        )

        assertEquals(
            "t2",
            lookalike(channel = Channel.CBE_BIRR, balanceAfterMinor = null),
        )
    }

    @Test
    fun `a reference on one side only still matches, on both sides it never does`() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(rawMessage("m2"))
        dao.insert(
            transaction(
                "t2",
                rawMessageId = "m2",
                channel = Channel.TELEBIRR,
                providerTxnId = "AAA1BBB2CC",
                occurredAt = T0,
            ),
        )

        assertEquals("t2", lookalike(channel = Channel.TELEBIRR, balanceAfterMinor = null))
        // Different references: had they been the same, the strong key would have
        // caught it already.
        assertNull(
            lookalike(
                channel = Channel.TELEBIRR,
                balanceAfterMinor = null,
                providerTxnId = "DDD3EEE4FF",
            ),
        )
        // The flagged-side reference only: still a candidate.
        assertEquals("t1", lookalike(providerTxnId = "DDD3EEE4FF"))
    }

    @Test
    fun `a manual cash entry is never a lookalike, a deleted row still is`() = runTest {
        dao.insert(
            transaction("cash", rawMessageId = null, channel = Channel.CASH, occurredAt = T0),
        )
        assertNull(lookalike(channel = Channel.CASH, balanceAfterMinor = null))

        dao.softDelete("t1", T0 + 1_000)
        assertEquals("t1", lookalike())
    }

    @Test
    fun `the nearest of several candidates is the one pointed at`() = runTest {
        db.rawMessageDao().insertIgnoringDuplicates(rawMessage("m2"))
        dao.insert(
            transaction(
                "t2",
                rawMessageId = "m2",
                channel = Channel.BANK,
                balanceAfterMinor = 120_000,
                occurredAt = T0 + 50_000,
            ),
        )

        assertEquals("t2", lookalike(occurredAt = T0 + 40_000))
        assertEquals("t1", lookalike(occurredAt = T0 + 10_000))
    }
}
