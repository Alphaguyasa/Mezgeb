package et.alpha.mezgeb.data.demo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.data.local.MezgebDatabase
import et.alpha.mezgeb.data.local.Migrations
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.data.repo.TransactionFilter
import et.alpha.mezgeb.data.repo.TransactionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The demo switch.
 *
 * This is the only control in the app that changes which file a shop's money is read
 * from, and the failure everyone would rather not have is not "the demo looked wrong" —
 * it is demo rows written into a real ledger, or a real ledger deleted to make room for
 * one. So the assertion that matters most here is a negative: after enabling, disabling
 * and enabling again, the real database still holds exactly what it held before, and
 * nothing the demo generated is anywhere near it.
 *
 * Unlike the rest of the data tests this one does not use the in-memory helper. The
 * whole mechanism is *which file gets opened*, and an in-memory database has no file.
 */
/*
 * A plain Application, not MezgebApplication. The real one launches the privacy purge
 * and the seeder against `mezgeb.db` on a background coroutine at startup, and this
 * test deletes and recreates that very file. The two raced, and on a fast CI runner
 * the purge queried a database whose tables had just been dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DemoModeControllerTest {

    private lateinit var context: Context
    private lateinit var store: DemoModeStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = DemoModeStore(context)
    }

    @After
    fun tearDown() {
        store.setEnabled(false)
        context.deleteDatabase(DemoModeStore.DEMO_DATABASE)
        context.deleteDatabase(MezgebDatabase.NAME)
    }

    // --- The safety property -------------------------------------------------------

    @Test
    fun `a real ledger survives demo mode being switched on and off`() = runTest {
        seedRealLedger("real_1", "real_2")

        val controller = controller()
        assertTrue(controller.enable().isSuccess)
        controller.disable()

        // Not "restored" — never opened. The real file is not named while the flag is
        // set, which is what the promise in the settings screen actually rests on.
        val survived = openReal { db ->
            TransactionRepository(db.transactionDao(), db.rawMessageDao())
                .listTransactions(TransactionFilter())
        }
        assertEquals(listOf("real_1", "real_2"), survived.map { it.id }.sorted())
    }

    @Test
    fun `nothing the demo generated ends up in the real database`() = runTest {
        seedRealLedger("real_1")

        controller().enable()

        val real = openReal { it.transactionCount() }
        // The demo builds hundreds of rows. Exactly one belongs to this shop.
        assertEquals(1, real)
    }

    @Test
    fun `the demo database is a different file entirely`() = runTest {
        seedRealLedger("real_1")

        controller().enable()

        val demoRows = openDemo { it.transactionCount() }
        assertTrue("the demo should be a whole shop, not one row", demoRows > 50)
        assertEquals("mezgeb-demo.db", DemoModeStore.DEMO_DATABASE)
    }

    // --- Enabling ------------------------------------------------------------------

    @Test
    fun `enabling seeds a shop and flips the flag`() = runTest {
        val controller = controller()

        assertFalse(controller.isEnabled())
        assertTrue(controller.enable().isSuccess)

        assertTrue(controller.isEnabled())
        assertEquals(DemoModeStore.DEMO_DATABASE, store.databaseName())
        assertTrue(openDemo { it.customerDao().getAll().size } > 0)
    }

    @Test
    fun `system categories are seeded alongside the demo rows`() = runTest {
        controller().enable()

        // Without these the demo's categorised transactions point at nothing, and the
        // reports screen — the one a stranger is most likely to open — renders blank.
        assertTrue(openDemo { it.categoryDao().count() } > 0)
    }

    @Test
    fun `enabling twice gives the same shop, not the same shop twice`() = runTest {
        val controller = controller()

        controller.enable()
        val first = openDemo { it.transactionCount() }
        controller.enable()
        val second = openDemo { it.transactionCount() }

        // The existing file is deleted and rebuilt. A demo shown twice that had
        // everything in it twice would look like a bug in the ledger itself.
        assertEquals(first, second)
    }

    // --- Failure ordering ----------------------------------------------------------

    @Test
    fun `a failed seed leaves the app pointing at the real ledger`() = runTest {
        seedRealLedger("real_1")

        val result = controller(clock = brokenClock()).enable()

        assertTrue(result.isFailure)
        // The whole reason the flag is set last. Had it been set first, a failed seed
        // would leave the app opening an empty or half-written demo file and calling
        // it the shop's books.
        assertFalse("a failed seed must not switch the app over", store.isEnabled())
        assertEquals(MezgebDatabase.NAME, store.databaseName())
        assertEquals(1, openReal { it.transactionCount() })
    }

    @Test
    fun `a failed seed reports the failure rather than throwing`() = runTest {
        val result = controller(clock = brokenClock()).enable()

        // The caller is a ViewModel that has to put a message on screen. An exception
        // crossing this boundary would be a crash on a settings toggle.
        assertTrue(result.isFailure)
    }

    // --- Disabling -----------------------------------------------------------------

    @Test
    fun `disabling points back at the real ledger and removes the demo file`() = runTest {
        val controller = controller()
        controller.enable()

        controller.disable()

        assertFalse(controller.isEnabled())
        assertEquals(MezgebDatabase.NAME, store.databaseName())
        assertFalse(
            "the demo file should not outlive the demo",
            context.databaseList().contains(DemoModeStore.DEMO_DATABASE),
        )
    }

    @Test
    fun `disabling when it was never enabled is harmless`() = runTest {
        seedRealLedger("real_1")

        controller().disable()

        assertFalse(store.isEnabled())
        assertEquals(1, openReal { it.transactionCount() })
    }

    // --- Helpers -------------------------------------------------------------------

    private fun controller(clock: Clock = fixedClock()) =
        DemoModeController(context = context, store = store, clock = clock)

    private fun fixedClock(): Clock =
        Clock.fixed(Instant.ofEpochMilli(T0), ZoneOffset.UTC)

    /**
     * A clock that throws when read, to fail the seed before it can write anything.
     *
     * `clock.millis()` is evaluated as the argument to `DemoData.build`, so the failure
     * lands inside `enable`'s runCatching and well before `store.setEnabled(true)` —
     * which is the window the enable-order exists to protect.
     */
    private fun brokenClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = error("no clock today")
    }

    /** No DAO exposes a bare count, and adding one for a test would be the tail wagging. */
    private suspend fun MezgebDatabase.transactionCount(): Int =
        TransactionRepository(transactionDao(), rawMessageDao())
            .listTransactions(TransactionFilter())
            .size

    private suspend fun seedRealLedger(vararg ids: String) {
        openReal { db ->
            db.transactionDao().insertAll(ids.map { transaction(it) })
        }
    }

    private suspend fun <T> openReal(block: suspend (MezgebDatabase) -> T): T =
        open(MezgebDatabase.NAME, block)

    private suspend fun <T> openDemo(block: suspend (MezgebDatabase) -> T): T =
        open(DemoModeStore.DEMO_DATABASE, block)

    private suspend fun <T> open(name: String, block: suspend (MezgebDatabase) -> T): T {
        val db = Room.databaseBuilder(context, MezgebDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .build()
        return try {
            block(db)
        } finally {
            db.close()
        }
    }
}
