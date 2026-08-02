package et.alpha.mezgeb.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * A real [SettingsRepository] over a real DataStore in a temp directory.
 *
 * Faking DataStore would fake the part most likely to be wrong — flow emission on
 * write — so the tests use the real thing and throw the file away afterwards.
 */
internal class SettingsHarness(directory: File) {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    val repository = SettingsRepository(
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(directory, "settings.preferences_pb") },
        ),
    )

    fun close() {
        scope.cancel()
    }
}
