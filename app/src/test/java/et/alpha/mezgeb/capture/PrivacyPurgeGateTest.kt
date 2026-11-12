package et.alpha.mezgeb.capture

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.rawMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The launch-time purge scans the whole message table, so it runs once per installed
 * version and database file, not on every start.
 */
class PrivacyPurgeGateTest : DatabaseTest() {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(folder.root, "settings.preferences_pb") },
        )
    }

    private var version = 10L
    private var databaseName = "mezgeb.db"

    private fun gate() = PrivacyPurgeGate(
        purge = PrivacyPurge(db.rawMessageDao()),
        dataStore = dataStore,
        databaseName = { databaseName },
        installedVersion = { version },
    )

    @After
    fun closeStore() = scope.cancel()

    private suspend fun storeMessageFromAPerson(id: String) {
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage(id, sender = "+251900000009", body = "see you at 5", contentHash = id),
        )
    }

    @Test
    fun `the first launch of a version scans`() = runTest {
        storeMessageFromAPerson("m1")

        assertEquals(1, gate().runIfDue()?.total)
        assertNull(db.rawMessageDao().getById("m1"))
    }

    @Test
    fun `later launches of the same version do not scan again`() = runTest {
        gate().runIfDue()
        // Stored after the scan, so only a second scan would remove it.
        storeMessageFromAPerson("m2")

        assertNull(gate().runIfDue())
        assertNotNull(db.rawMessageDao().getById("m2"))
    }

    @Test
    fun `a new version scans again`() = runTest {
        gate().runIfDue()
        storeMessageFromAPerson("m3")

        version = 11L

        assertEquals(1, gate().runIfDue()?.total)
        assertNull(db.rawMessageDao().getById("m3"))
        assertNull(gate().runIfDue())
    }

    @Test
    fun `a scan of the demo database does not count for the real ledger`() = runTest {
        databaseName = "mezgeb_demo.db"
        gate().runIfDue()
        storeMessageFromAPerson("m4")

        databaseName = "mezgeb.db"

        assertEquals(1, gate().runIfDue()?.total)
        assertNull(db.rawMessageDao().getById("m4"))
    }
}
