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

    /**
     * v2 → v3: a reconciliation period records when it was opened.
     *
     * The default of 0 is deliberate rather than "now": an existing open period was
     * opened at an unknown time, and 0 means every edit inside it counts as having
     * happened since — which over-reports rather than under-reports. Nothing is
     * hidden from the person reconciling.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE reconciliation_sessions ADD COLUMN openedAt INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    /**
     * v3 → v4: "a human edited a parsed value" and "a human touched this row" stop
     * sharing a column.
     *
     * `editedAt` was carrying both meanings, so filing a transaction under a category
     * marked it as edited and the ledger showed the marker on a row nobody had edited.
     *
     * The backfill copies `editedAt` into `touchedAt` rather than the other way round,
     * because every old timestamp is known to be a touch — an edit, a categorisation, a
     * delete or a restore — while only some of them were edits. Which ones is not
     * recoverable: the column that would have said is the one being split.
     *
     * So `editedAt` is left as it stands. Existing rows keep a marker some of them have
     * not earned, and it clears the first time each is genuinely edited. The choice is
     * the same one [MIGRATION_2_3] made — over-report rather than quietly erase
     * something a shop might have been relying on.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN touchedAt INTEGER")
            db.execSQL("UPDATE transactions SET touchedAt = editedAt")
        }
    }

    /**
     * v4 → v5: a parser rule remembers how much it was trusted.
     *
     * `confidence` existed in the bundled rule JSON and in the induction engine from the
     * beginning, and was dropped on the way into the database because there was no
     * column for it. Every rule therefore reported 1.0 once stored — a bundled rule that
     * declared 0.9 and an induced rule that declared 0.8 both came back claiming
     * certainty.
     *
     * The default of 1.0 for existing rows is the honest choice rather than the
     * flattering one. The value each old row *should* have is knowable — the bundled
     * rules are re-seeded from the asset on next launch, which overwrites them with
     * their real figures — but an induced rule's 0.8 is gone and cannot be recovered
     * from the row. Defaulting those to 1.0 over-states them, which is why the seeder
     * re-asserts the bundled values rather than leaving the default to stand in.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE parser_rules ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0",
            )
        }
    }

    /** Everything, in order, for the database builder. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
    )
}
