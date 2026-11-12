package et.alpha.mezgeb.capture

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.data.demo.DemoModeStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether [PrivacyPurge] has to scan the stored messages on this launch.
 *
 * The purge pages through every row of `raw_messages`. Running it on every start
 * cost a full scan each time to find nothing, because [CaptureCoordinator] already
 * refuses both kinds of message on the way in. What can change the answer is a new
 * build (a new credential pattern, a sender dropped from the allowlist) or a restore
 * (a backup written by an older build); the restore runs its own purge, see
 * `BackupRepository.restore`. So the scan runs once per installed version, per
 * database file.
 *
 * The installed version code stands in for a "purge rules version". A hand-kept
 * number would be one more thing to forget when the detector changes; every release
 * already bumps the version code, so tying the scan to it cannot fall behind.
 *
 * The marker is keyed by database file name. Demo mode opens a different file, and
 * a purge done there must not count as done for the real ledger.
 */
@Singleton
class PrivacyPurgeGate internal constructor(
    private val purge: PrivacyPurge,
    private val dataStore: DataStore<Preferences>,
    private val databaseName: () -> String,
    private val installedVersion: () -> Long,
) {

    @Inject
    constructor(
        purge: PrivacyPurge,
        dataStore: DataStore<Preferences>,
        demoMode: DemoModeStore,
        @ApplicationContext context: Context,
    ) : this(
        purge = purge,
        dataStore = dataStore,
        databaseName = demoMode::databaseName,
        installedVersion = { context.installedVersionCode() },
    )

    /**
     * Runs the purge if this database has not been scanned by this version yet.
     *
     * @return what was removed, or null when the scan was not needed.
     */
    suspend fun runIfDue(): PurgeSummary? {
        val key = longPreferencesKey(KEY_PREFIX + databaseName())
        val version = installedVersion()
        if (dataStore.data.first()[key] == version) return null

        val summary = purge.run()
        // Only after the scan finished: a scan cut short by a failure has to run again.
        dataStore.edit { it[key] = version }
        return summary
    }

    private companion object {
        const val KEY_PREFIX = "privacy_purge_version_"
    }
}

// The (String, Int) overload is deprecated from API 33 in favour of one taking
// PackageInfoFlags, which does not exist below 33. minSdk is 26, and flags of 0 ask for
// nothing, so the old overload is the one call that works on every supported version.
@Suppress("DEPRECATION")
private fun Context.installedVersionCode(): Long =
    PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
