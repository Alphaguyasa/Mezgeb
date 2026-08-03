package et.alpha.mezgeb.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The first migration, and the first test of one.
 *
 * There is no `fallbackToDestructiveMigration` in this project. That decision is only
 * safe if the migrations are right: the alternative to a tested migration is not a
 * crash, it is a shop's ledger quietly emptying on an app update.
 *
 * The old database is built from **the committed schema JSON**, not from DDL copied
 * into this file. `app/schemas/…/1.json` is what version 1 actually was, so a test
 * that reconstructs v1 from anywhere else is testing a fiction. Room then opens the
 * result at version 2 and validates it against the schema the code compiles to — if
 * the migration produced anything other than exactly that, the open throws.
 *
 * (Room's own `MigrationTestHelper` reads schemas from instrumentation assets, which
 * do not exist in a Robolectric unit test. This runs on every build instead of only
 * when someone remembers to plug in a phone.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath(TEST_DB)
        databaseFile.parentFile?.mkdirs()
        databaseFile.delete()
    }

    @After
    fun tearDown() {
        databaseFile.delete()
    }

    @Test
    fun `v1 to v2 adds archiving without touching a customer's data`() = runTest {
        createVersionOne { db ->
            db.execSQL(
                """
                INSERT INTO customers (id, name, phone, note, createdAt)
                VALUES ('c1', 'Abebe Bekele', '+251911000001', 'pays weekly', 1782950400000)
                """.trimIndent(),
            )
        }

        val migrated = openAtCurrentVersion()

        val customer = migrated.customerDao().getById("c1")
        assertNotNull("the migration lost a customer", customer)
        assertEquals("Abebe Bekele", customer?.name)
        assertEquals("+251911000001", customer?.phone)
        assertEquals("pays weekly", customer?.note)
        // Existing customers are on the books, which is what they were.
        assertEquals(false, customer?.isArchived)
        migrated.close()
    }

    @Test
    fun `credit history survives the migration`() = runTest {
        createVersionOne { db ->
            db.execSQL(
                """
                INSERT INTO customers (id, name, phone, note, createdAt)
                VALUES ('c1', 'Abebe', NULL, NULL, 1782950400000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO credit_entries
                    (id, customerId, kind, amountMinor, occurredAt, note, linkedTransactionId)
                VALUES ('e1', 'c1', 'CREDIT_GIVEN', 50000, 1782950400000, NULL, NULL)
                """.trimIndent(),
            )
        }

        val migrated = openAtCurrentVersion()

        val balance = migrated.customerDao().getById("c1")
        assertNotNull(balance)
        assertEquals(50_000L, migrated.creditEntryDao().getById("e1")?.amountMinor)
        migrated.close()
    }

    @Test
    fun `a ledger written before the update is still readable after it`() = runTest {
        createVersionOne { db ->
            db.execSQL(
                """
                INSERT INTO raw_messages (id, sender, body, contentHash, receivedAt, source, parseState, matchedRuleId)
                VALUES ('m1', 'CBE', 'credited with ETB 450.00', 'h1', 1782950400000, 'NOTIFICATION', 'PARSED', 'r1')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO transactions
                    (id, rawMessageId, direction, amountMinor, currency, channel, counterpartyName,
                     counterpartyPhone, providerTxnId, balanceAfterMinor, occurredAt, categoryId,
                     note, isReconciled, reconciliationSessionId, isDeleted, editedAt)
                VALUES ('t1', 'm1', 'IN', 45000, 'ETB', 'BANK', NULL, NULL, NULL, NULL,
                        1782950400000, NULL, NULL, 0, NULL, 0, NULL)
                """.trimIndent(),
            )
        }

        val migrated = openAtCurrentVersion()

        // The point of the whole no-destructive-migration rule.
        assertEquals(45_000L, migrated.transactionDao().getById("t1")?.amountMinor)
        assertEquals("PARSED", migrated.rawMessageDao().getById("m1")?.parseState?.name)
        migrated.close()
    }

    @Test
    fun `v3 to v4 carries every old timestamp across as a touch`() = runTest {
        createVersionOne { db ->
            db.execSQL(
                """
                INSERT INTO transactions
                    (id, rawMessageId, direction, amountMinor, currency, channel, counterpartyName,
                     counterpartyPhone, providerTxnId, balanceAfterMinor, occurredAt, categoryId,
                     note, isReconciled, reconciliationSessionId, isDeleted, editedAt)
                VALUES ('edited', NULL, 'IN', 45000, 'ETB', 'BANK', NULL, NULL, NULL, NULL,
                        1782950400000, NULL, NULL, 0, NULL, 0, 1782950500000),
                       ('untouched', NULL, 'IN', 20000, 'ETB', 'BANK', NULL, NULL, NULL, NULL,
                        1782950400000, NULL, NULL, 0, NULL, 0, NULL)
                """.trimIndent(),
            )
        }

        val migrated = openAtCurrentVersion()
        val dao = migrated.transactionDao()

        // Every pre-split timestamp is known to be a touch, so it becomes one.
        assertEquals(1782950500000L, dao.getById("edited")?.touchedAt)
        // And is left in editedAt, because which old rows were really edits is exactly
        // the information the single column failed to keep. Over-report, never erase.
        assertEquals(1782950500000L, dao.getById("edited")?.editedAt)

        // A row nobody ever touched does not acquire a timestamp from the migration.
        assertNull(dao.getById("untouched")?.touchedAt)
        assertNull(dao.getById("untouched")?.editedAt)
        migrated.close()
    }

    @Test
    fun `v1 straight to the current version, through every migration in turn`() = runTest {
        // Someone who has not opened the app since version 1 runs all of them at once.
        createVersionOne { db ->
            db.execSQL(
                """
                INSERT INTO reconciliation_sessions
                    (id, periodStart, periodEnd, expectedTotalMinor, capturedInMinor,
                     capturedOutMinor, cashRecordedMinor, creditGivenMinor,
                     creditRepaidMinor, status, closedAt, note)
                VALUES ('s1', 1782950400000, 1783036799999, 40000, 20000, 0, 10000, 0, 0,
                        'CLOSED', 1783036799999, NULL)
                """.trimIndent(),
            )
        }

        val migrated = openAtCurrentVersion()

        val session = migrated.reconciliationSessionDao().getById("s1")
        assertNotNull("a closed period must survive every migration", session)
        // The figures a shop owner already agreed with are still those figures.
        assertEquals(40_000L, session?.expectedTotalMinor)
        assertEquals(20_000L, session?.capturedInMinor)
        assertEquals(10_000L, session?.cashRecordedMinor)
        // A period opened before the column existed reports an unknown opening time as
        // 0, which counts every edit as recent. Over-reporting, never hiding.
        assertEquals(0L, session?.openedAt)
        migrated.close()
    }

    @Test
    fun `v4 to v5 gives every existing rule a confidence, and keeps the rule`() = runTest {
        createVersionOne { db ->
            db.execSQL(
                """
                INSERT INTO parser_rules
                    (id, name, senderPattern, channel, bodyPattern, fieldMapJson,
                     directionStrategy, directionKeywordsJson, version, isUserCreated,
                     isEnabled, priority, createdAt, matchCount)
                VALUES ('taught', 'A rule the shop taught itself', 'CBE', 'BANK',
                        'credited with ETB (?<amount>[0-9.,]+)', '{"amount":"AMOUNT"}',
                        'FIXED_IN', NULL, 1, 1, 1, 0, 1782950400000, 47)
                """.trimIndent(),
            )
        }

        val migrated = openAtCurrentVersion()
        val rule = migrated.parserRuleDao().getById("taught")

        // The rule a shop taught itself is the one thing in this table that cannot be
        // regenerated from the bundled asset. Losing it to a migration would mean
        // teaching it again.
        assertNotNull("the migration lost a user-created rule", rule)
        assertEquals(true, rule?.isUserCreated)
        assertEquals(47L, rule?.matchCount)

        // 1.0 by default, and knowingly wrong for this row: it was induced, so its real
        // figure was 0.8, and that is not recoverable from a row that never stored it.
        // Bundled rules get theirs back on the next seed; induced ones cannot.
        assertEquals(1.0f, rule?.confidence)
        migrated.close()
    }

    // --- Building version 1 from what version 1 actually was ---------------------------

    /**
     * Recreates the v1 database exactly as Room shipped it, from the committed schema,
     * then hands it to [seed] for rows.
     */
    private fun createVersionOne(seed: (SupportSQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(schemaFile(1).readText()).jsonObject
            .getValue("database").jsonObject
        val identityHash = schema.getValue("identityHash").jsonPrimitive.content

        val statements = buildList {
            schema.getValue("entities").jsonArray.forEach { entityElement ->
                val entity = entityElement.jsonObject
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                add(
                    entity.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName),
                )
                entity["indices"]?.jsonArray?.forEach { index ->
                    add(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName),
                    )
                }
            }
        }

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            statements.forEach(db::execSQL)
                            // Room refuses to open a database it cannot recognise. The
                            // identity hash is how it recognises one, and it is in the
                            // schema file alongside everything else.
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS room_master_table " +
                                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
                            )
                            db.execSQL(
                                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                    "VALUES (42, '$identityHash')",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { seed(it) }
        helper.close()
    }

    /**
     * Opens the database with the real builder. Room runs the migration and then
     * validates the result against the schema the app compiles to; a migration that
     * produced anything else fails here rather than in a shop.
     */
    private fun openAtCurrentVersion(): MezgebDatabase =
        Room.databaseBuilder(context, MezgebDatabase::class.java, TEST_DB)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()

    private fun schemaFile(version: Int) =
        File("schemas/${MezgebDatabase::class.java.canonicalName}/$version.json")

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
