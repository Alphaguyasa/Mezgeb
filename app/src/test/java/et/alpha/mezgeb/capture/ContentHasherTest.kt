package et.alpha.mezgeb.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentHasherTest {

    @Test
    fun `the same message hashes the same way twice`() {
        val body = "Dear customer, you have received ETB 450.00 from ABEBE."
        assertEquals(ContentHasher.hash("CBE", body), ContentHasher.hash("CBE", body))
    }

    @Test
    fun `sender decoration does not change the hash`() {
        val body = "you have received ETB 450.00"
        val fromNotification = ContentHasher.hash("SMS from CBE", body)
        val fromInbox = ContentHasher.hash("cbe", body)

        assertEquals(fromNotification, fromInbox)
    }

    @Test
    fun `case whitespace and trailing punctuation do not change the hash`() {
        // The same payment as the messaging app renders it, and as the inbox stores it.
        val posted = "Dear  Customer,\nyou have received ETB 450.00.\r\n"
        val stored = "dear customer you have received ETB 450.00"

        assertEquals(ContentHasher.hash("CBE", posted), ContentHasher.hash("CBE", stored))
    }

    @Test
    fun `a different sender is a different message`() {
        val body = "you have received ETB 450.00"
        assertNotEquals(ContentHasher.hash("CBE", body), ContentHasher.hash("EBIRR", body))
    }

    @Test
    fun `amounts that differ only by a decimal point do not collide`() {
        // The regression that made this hasher keep punctuation between digits.
        // Stripping all punctuation turns both of these into "...45000", so the second
        // payment would be silently dropped as a duplicate of the first.
        val small = ContentHasher.hash("CBE", "you have received ETB 450.00")
        val large = ContentHasher.hash("CBE", "you have received ETB 45000")

        assertNotEquals(small, large)
    }

    @Test
    fun `thousands separators survive canonicalisation`() {
        assertEquals("etb 1,500.00 received", ContentHasher.canonicalBody("ETB 1,500.00 received!"))
    }

    @Test
    fun `canonicalisation drops decoration but keeps the words`() {
        assertEquals(
            "credited with etb 200.50 ref ab12",
            ContentHasher.canonicalBody("Credited with (ETB 200.50) — ref: AB12."),
        )
    }

    @Test
    fun `amharic text survives canonicalisation`() {
        assertEquals("ብር 450.00 ደርሶዎታል", ContentHasher.canonicalBody("ብር 450.00 ደርሶዎታል።"))
    }
}
