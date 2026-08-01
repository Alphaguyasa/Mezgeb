package et.alpha.mezgeb.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import et.alpha.mezgeb.domain.model.CaptureSource
import et.alpha.mezgeb.domain.model.ParseState

class RawMessageDaoTest : DatabaseTest() {

    private val dao get() = db.rawMessageDao()

    @Test
    fun `insert returns a rowId for a new message`() = runTest {
        val rowId = dao.insertIgnoringDuplicates(rawMessage("m1"))
        assertTrue("expected a real rowId, got $rowId", rowId != -1L)
        assertNotNull(dao.getById("m1"))
    }

    @Test
    fun `duplicate contentHash is rejected by the unique index`() = runTest {
        dao.insertIgnoringDuplicates(rawMessage("m1", contentHash = "same"))
        val second = dao.insertIgnoringDuplicates(rawMessage("m2", contentHash = "same"))

        assertEquals(-1L, second)
        assertNull("the duplicate must not have been stored", dao.getById("m2"))
    }

    @Test
    fun `the same payment from both sources deduplicates on contentHash`() = runTest {
        // The notification and the SMS import see the same text a few hundred
        // milliseconds apart. Timestamps are deliberately not part of the hash, so the
        // second one must lose.
        dao.insertIgnoringDuplicates(
            rawMessage(
                id = "from_notification",
                contentHash = "telebirr|received|450",
                receivedAt = T0,
                source = CaptureSource.NOTIFICATION,
            ),
        )
        val second = dao.insertIgnoringDuplicates(
            rawMessage(
                id = "from_sms",
                contentHash = "telebirr|received|450",
                receivedAt = T0 + 380,
                source = CaptureSource.SMS_IMPORT,
            ),
        )

        assertEquals(-1L, second)
        assertEquals(CaptureSource.NOTIFICATION, dao.getById("from_notification")?.source)
    }

    @Test
    fun `getByContentHash finds the stored message`() = runTest {
        dao.insertIgnoringDuplicates(rawMessage("m1", contentHash = "abc"))
        assertEquals("m1", dao.getByContentHash("abc")?.id)
        assertNull(dao.getByContentHash("nope"))
    }

    @Test
    fun `observeByParseState filters and orders newest first`() = runTest {
        dao.insertAllIgnoringDuplicates(
            listOf(
                rawMessage("a", parseState = ParseState.UNMATCHED, receivedAt = T0),
                rawMessage("b", parseState = ParseState.UNMATCHED, receivedAt = T0 + DAY),
                rawMessage("c", parseState = ParseState.PARSED, receivedAt = T0 + 2 * DAY),
            ),
        )

        val unmatched = dao.observeByParseState(ParseState.UNMATCHED).first()

        assertEquals(listOf("b", "a"), unmatched.map { it.id })
    }

    @Test
    fun `unmatched messages group by sender with counts and newest timestamp`() = runTest {
        dao.insertAllIgnoringDuplicates(
            listOf(
                rawMessage("a", sender = "CBE", parseState = ParseState.UNMATCHED, receivedAt = T0),
                rawMessage("b", sender = "CBE", parseState = ParseState.UNMATCHED, receivedAt = T0 + DAY),
                rawMessage("c", sender = "awash", parseState = ParseState.UNMATCHED, receivedAt = T0 + 3 * DAY),
                // Parsed messages must not appear in the review queue.
                rawMessage("d", sender = "CBE", parseState = ParseState.PARSED, receivedAt = T0 + 9 * DAY),
            ),
        )

        val groups = dao.observeUnmatchedGroupedBySender().first()

        assertEquals(2, groups.size)
        // Ordered by most recent activity, so awash comes first.
        assertEquals("awash", groups[0].sender)
        assertEquals(1, groups[0].messageCount)
        assertEquals("CBE", groups[1].sender)
        assertEquals(2, groups[1].messageCount)
        assertEquals(T0 + DAY, groups[1].latestReceivedAt)
    }

    @Test
    fun `markResolved moves a message out of the queue and records the rule`() = runTest {
        dao.insertIgnoringDuplicates(rawMessage("m1", parseState = ParseState.UNMATCHED))

        dao.markResolved("m1", ParseState.PARSED, matchedRuleId = "rule_7")

        val stored = dao.getById("m1")
        assertEquals(ParseState.PARSED, stored?.parseState)
        assertEquals("rule_7", stored?.matchedRuleId)
        assertEquals(0, dao.observeCountByParseState(ParseState.UNMATCHED).first())
    }

    @Test
    fun `getUnmatchedBySender returns only that sender's unmatched messages`() = runTest {
        dao.insertAllIgnoringDuplicates(
            listOf(
                rawMessage("a", sender = "CBE", parseState = ParseState.UNMATCHED),
                rawMessage("b", sender = "telebirr", parseState = ParseState.UNMATCHED),
                rawMessage("c", sender = "CBE", parseState = ParseState.IGNORED),
            ),
        )

        val result = dao.getUnmatchedBySender("CBE")

        assertEquals(listOf("a"), result.map { it.id })
    }
}
