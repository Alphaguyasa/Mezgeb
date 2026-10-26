package et.alpha.mezgeb.domain.backup

/** Why Today asks for a backup. The two need different words. */
enum class BackupReminder {
    /** Nothing on this phone has ever been backed up. */
    NEVER_BACKED_UP,

    /** There is a backup, but it is older than [BackupReminderPolicy.INTERVAL_MILLIS]. */
    STALE,
}

/**
 * When Today should ask the owner to save a backup (ROADMAP R23).
 *
 * The app is offline and has no sync, so a lost or broken phone takes every ledger
 * and credit entry that was never backed up with it. Nothing else in the app would
 * ever say so.
 */
object BackupReminderPolicy {

    /** Thirty days: long enough not to nag, short enough that a loss is one month. */
    const val INTERVAL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * @param lastBackupAt when a backup was last written, or null for never.
     * @param firstRunAt when the app first ran. A shop that has never backed up is
     *   measured from here, so a new install is not asked on its first morning.
     *   Null (not stamped yet) asks nothing.
     * @param dismissedAt when the owner last said "not now". It quiets the reminder
     *   for one interval, not for good: a dismissed warning must come back.
     * @param hasTransactions an empty ledger has nothing to lose.
     */
    fun reminderFor(
        now: Long,
        lastBackupAt: Long?,
        firstRunAt: Long?,
        dismissedAt: Long?,
        hasTransactions: Boolean,
    ): BackupReminder? {
        if (!hasTransactions) return null
        val since = lastBackupAt ?: firstRunAt ?: return null
        if (now - since < INTERVAL_MILLIS) return null
        if (dismissedAt != null && now - dismissedAt < INTERVAL_MILLIS) return null
        return if (lastBackupAt == null) BackupReminder.NEVER_BACKED_UP else BackupReminder.STALE
    }
}
