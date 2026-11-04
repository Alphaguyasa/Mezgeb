package et.alpha.mezgeb.capture.sms

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** One inbox row, reduced to the three columns capture cares about. */
data class InboxRow(
    val address: String,
    val body: String,
    /** Epoch millis, the provider's `date` column. Also the import cursor. */
    val date: Long,
)

/**
 * One page of the inbox: the rows worth capturing, and how many cursor rows were read
 * to get them.
 *
 * The two differ when a row has no address or no body and is skipped. The import
 * pages and stops on [rowsRead], never on `rows.size`: one blank row in a full page
 * once made a 499-row page look like the last one, and an inbox of thousands stopped
 * after its first 500 messages (ROADMAP R45).
 */
data class InboxPage(val rows: List<InboxRow>, val rowsRead: Int)

/**
 * Where an import reads its rows from. One implementation, plus whatever a test
 * needs — the worker's job is the funnel and the cursor, not the content provider.
 */
interface InboxSource {

    /** How many rows an import starting at `since` will look at. For the progress bar. */
    fun count(since: Long): Int

    /**
     * Up to `limit` cursor rows newer than `since`, oldest first, from `offset`.
     * `offset` counts cursor rows, skipped ones included.
     */
    fun page(since: Long, offset: Int, limit: Int): InboxPage
}

/**
 * Reads the messaging inbox, a page at a time.
 *
 * Paging matters: a phone that has been in use for a few years holds thousands of
 * messages, and loading them all before capture starts means a long silence followed
 * by an out-of-memory crash on a low-end device.
 *
 * `direct` flavor only. This file is the reason the restricted permission exists,
 * and it is why it exists in exactly one source set.
 */
class SmsInboxReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : InboxSource {

    override fun count(since: Long): Int = context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms._ID),
        SELECTION,
        arrayOf(since.toString()),
        null,
    )?.use { it.count } ?: 0

    /**
     * `since` stays fixed for a whole import while `offset` advances, so a message
     * arriving mid-import cannot shift the window and make a row be skipped. The new
     * message is captured by the listener anyway, and would be a duplicate here.
     */
    override fun page(since: Long, offset: Int, limit: Int): InboxPage =
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            PROJECTION,
            SELECTION,
            arrayOf(since.toString()),
            // The SMS provider passes sortOrder through to SQLite, which is what makes
            // LIMIT/OFFSET here work at all. It is not part of the documented contract.
            "${Telephony.Sms.DATE} ASC LIMIT $limit OFFSET $offset",
        )?.use(::readPage) ?: InboxPage(emptyList(), rowsRead = 0)

    internal companion object {

        val PROJECTION = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        private const val SELECTION = "${Telephony.Sms.DATE} > ?"

        /**
         * Visible for testing: the mapping is where a null address or a null body
         * would otherwise become a crash halfway through an eight-thousand message
         * import.
         */
        fun readPage(cursor: Cursor): InboxPage {
            val addressColumn = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateColumn = cursor.getColumnIndex(Telephony.Sms.DATE)
            // Every row was still read, so the import moves past them instead of
            // reading the same page again.
            if (addressColumn < 0 || bodyColumn < 0 || dateColumn < 0) {
                return InboxPage(emptyList(), rowsRead = cursor.count)
            }

            val rows = ArrayList<InboxRow>(cursor.count)
            var read = 0
            while (cursor.moveToNext()) {
                read++
                val address = cursor.getString(addressColumn)
                val body = cursor.getString(bodyColumn)
                val date = cursor.getLong(dateColumn)
                if (address.isNullOrBlank() || body.isNullOrBlank()) continue
                rows += InboxRow(address = address, body = body, date = date)
            }
            return InboxPage(rows, rowsRead = read)
        }
    }
}
