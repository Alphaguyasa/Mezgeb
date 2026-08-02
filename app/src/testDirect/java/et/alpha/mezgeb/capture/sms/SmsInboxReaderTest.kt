package et.alpha.mezgeb.capture.sms

import android.database.MatrixCursor
import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The row mapping, tested against a cursor rather than against a phone.
 *
 * The interesting cases are all malformed rows: an inbox that has been through a
 * device migration or a third-party backup app contains rows with no address and
 * rows with no body, and hitting one of those halfway through an eight-thousand
 * message import must not end the import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsInboxReaderTest {

    @Test
    fun `rows become messages`() {
        val cursor = cursor(
            arrayOf("CBE", "credited with ETB 450.00", 1_700_000_000_000L),
            arrayOf("127", "you have received ETB 20.00", 1_700_000_060_000L),
        )

        val rows = SmsInboxReader.readRows(cursor)

        assertEquals(2, rows.size)
        assertEquals("CBE", rows[0].address)
        assertEquals("credited with ETB 450.00", rows[0].body)
        assertEquals(1_700_000_000_000L, rows[0].date)
    }

    @Test
    fun `rows with no address or no body are skipped, not thrown`() {
        val cursor = cursor(
            arrayOf(null, "credited with ETB 450.00", 1L),
            arrayOf("CBE", null, 2L),
            arrayOf("  ", "credited with ETB 450.00", 3L),
            arrayOf("CBE", "credited with ETB 12.00", 4L),
        )

        val rows = SmsInboxReader.readRows(cursor)

        assertEquals(1, rows.size)
        assertEquals(4L, rows[0].date)
    }

    @Test
    fun `a cursor without the expected columns yields nothing`() {
        // Some OEM providers rename or omit columns. Nothing is better than a crash.
        val cursor = MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(1)) }

        assertTrue(SmsInboxReader.readRows(cursor).isEmpty())
    }

    private fun cursor(vararg rows: Array<Any?>): MatrixCursor = MatrixCursor(
        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
    ).apply {
        rows.forEach { addRow(it) }
    }
}
