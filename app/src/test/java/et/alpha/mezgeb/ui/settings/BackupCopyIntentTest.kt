package et.alpha.mezgeb.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Send a copy" after a backup. The file is the shop's whole ledger, encrypted, so the
 * intent has to carry exactly that file and pass on only a read grant to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCopyIntentTest {

    private val uri = Uri.parse("content://com.example.documents/document/mezgeb-backup.mzgb")

    @Test
    fun `the chooser carries the backup file with a read grant only`() {
        val chooser = backupCopyIntent(uri, "Send a copy")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        send!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(uri, IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, (send.clipData ?: chooser.clipData)?.getItemAt(0)?.uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // Never a write grant: the receiving app may read the copy, not change the file.
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
