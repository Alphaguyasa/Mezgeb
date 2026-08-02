package et.alpha.mezgeb.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema migrations, in order.
 *
 * There is no `fallbackToDestructiveMigration` anywhere in this project, deliberately
 * (see [et.alpha.mezgeb.di.DatabaseModule]). A missing or wrong migration must fail
 * loudly in development rather than quietly wipe a shop's ledger in the field, so
 * every one of these is written by hand and tested against the previous schema.
 */
object Migrations {

    /**
     * v1 → v2: customers can be archived.
     *
     * Additive and non-destructive: one column with a default, so every existing row
     * keeps its data and becomes un-archived, which is what it was.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE customers ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /** Everything, in order, for the database builder. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
