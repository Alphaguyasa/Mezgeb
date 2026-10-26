package et.alpha.mezgeb.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the capture layer has to remember between launches.
 *
 * DataStore rather than SharedPreferences, per CONVENTIONS.md — the reads are flows, so
 * capture health updates the UI without polling, and the writes are transactional,
 * so an import cursor cannot be half-written when the process dies mid-import.
 *
 * Nothing here is user content. Timestamps and flags only; message text lives in the
 * database and nowhere else.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    // --- Capture health -------------------------------------------------------------

    /** When the notification listener last handed a message to the funnel. */
    fun observeLastCaptureAt(): Flow<Long?> = dataStore.data.map { it[LAST_CAPTURE_AT] }

    suspend fun recordCapture(at: Long) {
        dataStore.edit { it[LAST_CAPTURE_AT] = at }
    }

    /**
     * When the app first ran. Silence is measured from here until the first capture,
     * so a listener that has never worked at all is still detectable — otherwise
     * "nothing captured yet" and "capture is broken" look identical.
     */
    fun observeFirstRunAt(): Flow<Long?> = dataStore.data.map { it[FIRST_RUN_AT] }

    /** Sets the first-run stamp if it is not already set. Idempotent. */
    suspend fun markFirstRun(now: Long) {
        dataStore.edit { prefs ->
            if (prefs[FIRST_RUN_AT] == null) prefs[FIRST_RUN_AT] = now
        }
    }

    /** Set when a boot or a resume finds notification access has been taken away. */
    fun observeAccessLostAt(): Flow<Long?> = dataStore.data.map { it[ACCESS_LOST_AT] }

    suspend fun recordAccessLost(at: Long?) {
        dataStore.edit { prefs ->
            if (at == null) prefs.remove(ACCESS_LOST_AT) else prefs[ACCESS_LOST_AT] = at
        }
    }

    // --- Onboarding -----------------------------------------------------------------

    fun observeOnboardingCompleted(): Flow<Boolean> =
        dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }

    suspend fun isOnboardingCompleted(): Boolean = observeOnboardingCompleted().first()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }

    /** The vendor battery-settings prompt is offered once, not on every launch. */
    fun observeBatteryHintShown(): Flow<Boolean> =
        dataStore.data.map { it[BATTERY_HINT_SHOWN] ?: false }

    suspend fun setBatteryHintShown(shown: Boolean) {
        dataStore.edit { it[BATTERY_HINT_SHOWN] = shown }
    }

    // --- Notification sources -------------------------------------------------------

    /**
     * Extra app packages whose notifications are worth reading, beyond the default
     * messaging app. Empty by default: see [et.alpha.mezgeb.capture.notification.NotificationSourceFilter].
     */
    fun observeWatchedPackages(): Flow<Set<String>> =
        dataStore.data.map { it[WATCHED_PACKAGES] ?: emptySet() }

    suspend fun setWatchedPackages(packages: Set<String>) {
        dataStore.edit { it[WATCHED_PACKAGES] = packages }
    }

    // --- Shop identity ----------------------------------------------------------------

    /**
     * What the shop calls itself, for the header of an exported summary.
     *
     * Null until someone says, and the UI falls back to the app's own name — an
     * export headed with a blank line is worse than one headed "Mezgeb".
     */
    fun observeShopName(): Flow<String?> = dataStore.data.map { it[SHOP_NAME] }

    suspend fun shopName(): String? = observeShopName().first()

    suspend fun setShopName(name: String?) {
        dataStore.edit { prefs ->
            if (name.isNullOrBlank()) prefs.remove(SHOP_NAME) else prefs[SHOP_NAME] = name.trim()
        }
    }

    // --- Credit book ------------------------------------------------------------------

    /** How many days a debt may sit before the customers screen calls it overdue. */
    fun observeOverdueThresholdDays(): Flow<Int> =
        dataStore.data.map { it[OVERDUE_THRESHOLD_DAYS] ?: DEFAULT_OVERDUE_DAYS }

    suspend fun setOverdueThresholdDays(days: Int) {
        dataStore.edit { it[OVERDUE_THRESHOLD_DAYS] = days.coerceAtLeast(1) }
    }

    /**
     * The reminder message, with `{name}` and `{amount}` placeholders.
     *
     * Stored rather than hard-coded because the wording a shop uses with its own
     * customers is theirs, not the app's — and because the default in one language is
     * the wrong tone in another. Null means "use the built-in default for the current
     * language", which is why this is not seeded with an English string on first run.
     */
    fun observeReminderTemplate(): Flow<String?> = dataStore.data.map { it[REMINDER_TEMPLATE] }

    suspend fun setReminderTemplate(template: String?) {
        dataStore.edit { prefs ->
            if (template.isNullOrBlank()) {
                prefs.remove(REMINDER_TEMPLATE)
            } else {
                prefs[REMINDER_TEMPLATE] = template
            }
        }
    }

    // --- Reconciliation ---------------------------------------------------------------

    /** On by default: the habit is the product, and a habit needs reminding into being. */
    fun observeReconcileRemindersEnabled(): Flow<Boolean> =
        dataStore.data.map { it[RECONCILE_REMINDERS] ?: true }

    suspend fun reconcileRemindersEnabled(): Boolean =
        observeReconcileRemindersEnabled().first()

    suspend fun setReconcileRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[RECONCILE_REMINDERS] = enabled }
    }

    suspend fun firstRunAt(): Long? = observeFirstRunAt().first()

    // --- Backup -----------------------------------------------------------------------

    /**
     * When a backup file was last written in full. Null means never.
     *
     * Kept here rather than in Room: it describes the database, so it must not be
     * inside it. A restore would otherwise bring back the stamp of whichever backup it
     * came from, and a backup would carry its own previous date (ROADMAP R23).
     */
    fun observeLastBackupAt(): Flow<Long?> = dataStore.data.map { it[LAST_BACKUP_AT] }

    suspend fun recordBackup(at: Long) {
        dataStore.edit { it[LAST_BACKUP_AT] = at }
    }

    /** When the owner last said "not now" to Today's backup reminder. */
    fun observeBackupReminderDismissedAt(): Flow<Long?> =
        dataStore.data.map { it[BACKUP_REMINDER_DISMISSED_AT] }

    suspend fun dismissBackupReminder(at: Long) {
        dataStore.edit { it[BACKUP_REMINDER_DISMISSED_AT] = at }
    }

    // --- SMS import (direct flavor only reads these) ---------------------------------

    /**
     * The `date` of the newest inbox row already imported. The next import starts
     * strictly after it, so re-running an import costs a cursor read rather than a
     * full re-scan of eight thousand messages.
     */
    fun observeSmsImportSince(): Flow<Long> = dataStore.data.map { it[SMS_IMPORT_SINCE] ?: 0L }

    suspend fun smsImportSince(): Long = observeSmsImportSince().first()

    suspend fun setSmsImportSince(date: Long) {
        dataStore.edit { prefs ->
            // Never move the cursor backwards: a partial import that saw older rows
            // must not make the next run re-read everything after them.
            val current = prefs[SMS_IMPORT_SINCE] ?: 0L
            if (date > current) prefs[SMS_IMPORT_SINCE] = date
        }
    }

    fun observeLastSmsImportAt(): Flow<Long?> = dataStore.data.map { it[LAST_SMS_IMPORT_AT] }

    suspend fun recordSmsImport(at: Long) {
        dataStore.edit { it[LAST_SMS_IMPORT_AT] = at }
    }

    private companion object {
        /** Mirrors [et.alpha.mezgeb.domain.credit.ComputeCustomerBalanceUseCase]. */
        const val DEFAULT_OVERDUE_DAYS = 30

        val SHOP_NAME = stringPreferencesKey("shop_name")
        val RECONCILE_REMINDERS = booleanPreferencesKey("reconcile_reminders_enabled")
        val OVERDUE_THRESHOLD_DAYS = intPreferencesKey("overdue_threshold_days")
        val REMINDER_TEMPLATE = stringPreferencesKey("reminder_template")

        val LAST_CAPTURE_AT = longPreferencesKey("last_capture_at")
        val FIRST_RUN_AT = longPreferencesKey("first_run_at")
        val ACCESS_LOST_AT = longPreferencesKey("notification_access_lost_at")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val BATTERY_HINT_SHOWN = booleanPreferencesKey("battery_hint_shown")
        val WATCHED_PACKAGES = stringSetPreferencesKey("watched_packages")
        val SMS_IMPORT_SINCE = longPreferencesKey("sms_import_since")
        val LAST_SMS_IMPORT_AT = longPreferencesKey("last_sms_import_at")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val BACKUP_REMINDER_DISMISSED_AT = longPreferencesKey("backup_reminder_dismissed_at")
    }
}
