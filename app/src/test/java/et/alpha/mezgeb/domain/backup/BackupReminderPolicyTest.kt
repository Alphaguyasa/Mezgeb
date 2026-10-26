package et.alpha.mezgeb.domain.backup

import et.alpha.mezgeb.domain.backup.BackupReminderPolicy.INTERVAL_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When Today asks for a backup (ROADMAP R23). Pure arithmetic, so every boundary is
 * pinned here rather than through a ViewModel.
 */
class BackupReminderPolicyTest {

    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `an empty ledger is never asked, however old the install`() {
        assertNull(remind(lastBackupAt = null, firstRunAt = now - 400 * day, hasTransactions = false))
        assertNull(remind(lastBackupAt = now - 400 * day, hasTransactions = false))
    }

    @Test
    fun `a new install is not asked on its first morning`() {
        assertNull(remind(lastBackupAt = null, firstRunAt = now - day))
    }

    @Test
    fun `a month without any backup asks, and says there has never been one`() {
        assertEquals(
            BackupReminder.NEVER_BACKED_UP,
            remind(lastBackupAt = null, firstRunAt = now - INTERVAL_MILLIS),
        )
    }

    @Test
    fun `without a first-run stamp nothing is asked`() {
        assertNull(remind(lastBackupAt = null, firstRunAt = null))
    }

    @Test
    fun `a recent backup asks nothing`() {
        assertNull(remind(lastBackupAt = now - (INTERVAL_MILLIS - 1)))
    }

    @Test
    fun `a backup thirty days old is stale`() {
        assertEquals(BackupReminder.STALE, remind(lastBackupAt = now - INTERVAL_MILLIS))
    }

    /** The backup date wins over the install date: the install is older than any backup. */
    @Test
    fun `a recent backup on an old install asks nothing`() {
        assertNull(remind(lastBackupAt = now - day, firstRunAt = now - 400 * day))
    }

    @Test
    fun `not now quiets a stale reminder for one interval`() {
        assertNull(remind(lastBackupAt = now - 90 * day, dismissedAt = now - 29 * day))
    }

    /** A dismissed warning must come back, or it was never given. */
    @Test
    fun `the reminder comes back an interval after not now`() {
        assertEquals(
            BackupReminder.STALE,
            remind(lastBackupAt = now - 90 * day, dismissedAt = now - INTERVAL_MILLIS),
        )
    }

    private fun remind(
        lastBackupAt: Long?,
        firstRunAt: Long? = now - 400 * day,
        dismissedAt: Long? = null,
        hasTransactions: Boolean = true,
    ) = BackupReminderPolicy.reminderFor(
        now = now,
        lastBackupAt = lastBackupAt,
        firstRunAt = firstRunAt,
        dismissedAt = dismissedAt,
        hasTransactions = hasTransactions,
    )
}
