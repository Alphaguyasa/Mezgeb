package et.alpha.mezgeb.data.demo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.data.local.MezgebDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The demo flag, and specifically whether writing it survives the process dying.
 *
 * Not an academic worry. The only caller flips this flag and then calls
 * `Runtime.exit(0)` immediately, because the flag decides which database file Room
 * opens and the restart *is* the mechanism. The write therefore has to be on disk by
 * the time `setEnabled` returns, not merely scheduled — `apply()` schedules, and
 * Android's flush of pending writes at process exit does not run when the process is
 * halted outright. That shipped, and demo mode did nothing at all on a real device.
 *
 * **Two tests that look reasonable and cannot catch it**, recorded so nobody writes
 * them a third time:
 *
 *  - *set it, build a second store, read it back.* `SharedPreferences` instances are
 *    cached per name per process, so the second store shares the first one's in-memory
 *    map and reads the value whether or not a byte reached storage.
 *  - *set it, then read the backing XML file.* Under Robolectric `apply()` completes
 *    inline, so the file has the value either way. This one was written first, passed
 *    against the buggy code, and would have been worse than no test — a green
 *    regression test for a bug that was still there.
 *
 * What actually discriminates is asserting the write was synchronous, which is the
 * requirement itself rather than an implementation detail: for this flag, "committed"
 * and "correct" are the same word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoModeStoreTest {

    private lateinit var realContext: Context

    @Before
    fun setUp() {
        realContext = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() = DemoModeStore(realContext).setEnabled(false)

    @Test
    fun `the flag is committed, never applied, because the caller kills the process`() {
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk(relaxed = true)
        val context: Context = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.commit() } returns true
        every { context.getSharedPreferences(any(), any()) } returns prefs

        DemoModeStore(context).setEnabled(true)

        verify(exactly = 1) { editor.commit() }
        // The failure this whole test exists for. apply() returns immediately and the
        // caller's Runtime.exit(0) then discards the pending write.
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `the flag decides which database file Room is asked to open`() {
        val store = DemoModeStore(realContext)
        store.setEnabled(false)
        assertEquals(MezgebDatabase.NAME, store.databaseName())

        store.setEnabled(true)

        // The real ledger's file is never named while the demo is on. That is what
        // "disabling restores the real database untouched" rests on: it is not
        // restored, it is never opened.
        assertEquals(DemoModeStore.DEMO_DATABASE, store.databaseName())
    }

    @Test
    fun `a fresh store reads what the previous one wrote`() {
        DemoModeStore(realContext).setEnabled(true)

        // Weak on its own, for the reason in the class note. Kept because it is the
        // property callers depend on, and it would catch a renamed key, which none of
        // the above would.
        assertTrue(DemoModeStore(realContext).isEnabled())
    }
}
