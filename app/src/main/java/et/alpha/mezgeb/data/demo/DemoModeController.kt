package et.alpha.mezgeb.data.demo

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.data.local.MezgebDatabase
import et.alpha.mezgeb.data.local.Migrations
import et.alpha.mezgeb.data.local.seed.DatabaseSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turning demo mode on and off.
 *
 * The demo database is built **before** the flag is flipped, using a throwaway Room
 * instance rather than the injected singleton. That ordering is the whole design: if
 * seeding fails, the flag was never set and the app is still pointing at the real
 * ledger, which is the failure everyone would rather have.
 *
 * The real database is not backed up, copied, or restored when demo mode is enabled.
 * It is simply never opened — [DemoModeStore.databaseName] returns the other file, and
 * Hilt builds the singleton around it at process start. "Untouched" here is literal.
 *
 * Switching requires a restart because the database is a `@Singleton`: reaching every
 * DAO, repository and open cursor to re-point them at a different file would mean an
 * indirection layer through the entire data package, in exchange for saving a shop
 * one relaunch of a demo it asked for.
 */
@Singleton
class DemoModeController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: DemoModeStore,
    private val clock: Clock,
) {

    fun isEnabled(): Boolean = store.isEnabled()

    /**
     * Builds and seeds the demo database, then switches to it.
     *
     * Idempotent from the caller's point of view: an existing demo file is deleted and
     * rebuilt, so a demo shown twice is the same shop both times and never a shop with
     * everything in it twice.
     */
    suspend fun enable(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.deleteDatabase(DemoModeStore.DEMO_DATABASE)

            val db = openDemoDatabase()
            try {
                val shop = DemoData.build(clock.millis())
                db.withTransaction {
                    db.categoryDao().insertIgnoringExisting(DatabaseSeeder.SYSTEM_CATEGORIES)
                    // Insert order is foreign-key order: messages before the
                    // transactions that reference them, customers before their entries.
                    db.rawMessageDao().insertAllIgnoringDuplicates(shop.rawMessages)
                    db.transactionDao().insertAll(shop.transactions)
                    shop.customers.forEach { db.customerDao().insert(it) }
                    db.creditEntryDao().insertAll(shop.creditEntries)
                    shop.sessions.forEach { db.reconciliationSessionDao().insert(it) }
                }
            } finally {
                db.close()
            }

            store.setEnabled(true)
        }
    }

    /**
     * Points the app back at the real ledger and deletes the demo file.
     *
     * The flag is cleared first: if the delete fails — a cursor still open somewhere,
     * a device that will not release the file — the app is already back on the real
     * database and a stale demo file is a few hundred kilobytes, not a problem.
     */
    suspend fun disable() = withContext(Dispatchers.IO) {
        store.setEnabled(false)
        runCatching { context.deleteDatabase(DemoModeStore.DEMO_DATABASE) }
        Unit
    }

    private fun openDemoDatabase(): MezgebDatabase =
        Room.databaseBuilder(context, MezgebDatabase::class.java, DemoModeStore.DEMO_DATABASE)
            .addMigrations(*Migrations.ALL)
            .build()
}
