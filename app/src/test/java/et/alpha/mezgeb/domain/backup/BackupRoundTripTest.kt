package et.alpha.mezgeb.domain.backup

import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.data.local.category
import et.alpha.mezgeb.data.local.creditEntry
import et.alpha.mezgeb.data.local.customer
import et.alpha.mezgeb.data.local.parserRule
import et.alpha.mezgeb.data.local.rawMessage
import et.alpha.mezgeb.data.local.transaction
import et.alpha.mezgeb.domain.model.CreditKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Seed → back up → wipe → restore → assert deep equality.
 *
 * The assertion is on the entities themselves rather than on counts. A backup that
 * restores the right *number* of transactions with one wrong amount is worse than one
 * that fails outright, because nobody would notice.
 */
class BackupRoundTripTest : DatabaseTest() {

    private lateinit var repository: BackupRepository
    private val passphrase = "correct horse battery staple".toCharArray()

    @Before
    fun setUp() {
        repository = BackupRepository(db, Json { ignoreUnknownKeys = true })
    }

    private suspend fun seed() {
        db.rawMessageDao().insertIgnoringDuplicates(
            rawMessage("m1", sender = "CBE", body = "credited with ETB 450.00", contentHash = "h1"),
        )
        db.transactionDao().insert(
            transaction(
                "t1",
                rawMessageId = "m1",
                amountMinor = 45_000,
                counterpartyName = "አበበ በቀለ",
                note = "note, with a comma",
                occurredAt = T0,
            ),
        )
        db.customerDao().insert(customer("c1", name = "አበበ", phone = "+251911000001"))
        db.creditEntryDao().insert(
            creditEntry("e1", "c1", CreditKind.CREDIT_GIVEN, 50_000, occurredAt = T0),
        )
        db.categoryDao().insert(category("cat_sales", name = "Sales"))
        db.parserRuleDao().insert(parserRule("r1"))
    }

    @Test
    fun `every table survives the round trip byte for byte`() = runTest {
        seed()

        val before = snapshot()
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)

        wipe()
        assertEquals(0, snapshot().rowCount)

        val result = repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase)

        assertTrue(result.isSuccess)
        assertEquals(before.rowCount, result.getOrThrow().rowsRestored)
        // Deep equality on the entities, not on counts.
        assertEquals(before, snapshot())
    }

    @Test
    fun `amharic text and awkward punctuation come back unchanged`() = runTest {
        seed()
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)
        wipe()

        repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase).getOrThrow()

        val restored = db.transactionDao().getById("t1")
        assertEquals("አበበ በቀለ", restored?.counterpartyName)
        assertEquals("note, with a comma", restored?.note)
        assertEquals("አበበ", db.customerDao().getById("c1")?.name)
    }

    @Test
    fun `the wrong passphrase restores nothing`() = runTest {
        seed()
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)
        val before = snapshot()

        val result = repository.restore(
            ByteArrayInputStream(file.toByteArray()),
            "not the passphrase".toCharArray(),
        )

        assertTrue(result.isFailure)
        assertEquals(
            RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT,
            (result.exceptionOrNull() as RestoreException).reason,
        )
        // The database was never touched: validation happens before the wipe.
        assertEquals(before, snapshot())
    }

    @Test
    fun `a file that is not a backup is refused before anything is cleared`() = runTest {
        seed()
        val before = snapshot()

        val result = repository.restore(
            ByteArrayInputStream("this is a photograph, not a ledger".toByteArray()),
            passphrase,
        )

        assertEquals(
            RestoreFailure.NOT_A_BACKUP,
            (result.exceptionOrNull() as RestoreException).reason,
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `a single flipped byte fails the tag rather than restoring a wrong number`() = runTest {
        seed()
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)
        val corrupted = file.toByteArray().also { it[it.size - 1] = (it.last() + 1).toByte() }
        val before = snapshot()

        val result = repository.restore(ByteArrayInputStream(corrupted), passphrase)

        // GCM authenticates as well as encrypts. Without the tag this would restore a
        // ledger with one silently wrong value in it.
        assertEquals(
            RestoreFailure.WRONG_PASSPHRASE_OR_CORRUPT,
            (result.exceptionOrNull() as RestoreException).reason,
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `a backup from a newer schema is refused, not partially applied`() = runTest {
        val payload = Json.encodeToString(BackupPayload())
        val file = ByteArrayOutputStream()
        BackupCrypto.write(
            json = payload,
            passphrase = passphrase,
            schemaVersion = BackupRepository.SCHEMA_VERSION + 1,
            output = file,
        )

        val result = repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase)

        assertEquals(
            RestoreFailure.UNSUPPORTED_SCHEMA,
            (result.exceptionOrNull() as RestoreException).reason,
        )
    }

    @Test
    fun `the backup schema version tracks the database version`() {
        // The in-memory database is built from the same @Database annotation as the
        // real one, so its version is the one that ships. When this fails, bump
        // SCHEMA_VERSION together with MezgebDatabase, and make sure a file written
        // at the old version still restores (see the next test).
        assertEquals(db.openHelper.readableDatabase.version, BackupRepository.SCHEMA_VERSION)
    }

    @Test
    fun `a new backup says which schema wrote it`() = runTest {
        seed()
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)

        val result = repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase)

        assertEquals(db.openHelper.readableDatabase.version, result.getOrThrow().schemaVersion)
    }

    @Test
    fun `a backup written at schema 5 still restores`() = runTest {
        seed()
        val before = snapshot()

        // A v5 file has no possibleDuplicateOfId on its transactions. Strip it
        // explicitly rather than trusting that a null default is never encoded.
        val v6 = Json.parseToJsonElement(Json.encodeToString(before)).jsonObject
        val v5Transactions = JsonArray(
            v6.getValue("transactions").jsonArray.map {
                JsonObject(it.jsonObject - "possibleDuplicateOfId")
            },
        )
        val v5 = JsonObject(v6 + ("transactions" to v5Transactions))
        val file = ByteArrayOutputStream()
        BackupCrypto.write(
            json = v5.toString(),
            passphrase = passphrase,
            schemaVersion = 5,
            output = file,
        )
        wipe()

        val result = repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase)

        assertEquals(5, result.getOrThrow().schemaVersion)
        assertEquals(before, snapshot())
        assertNull(db.transactionDao().getById("t1")?.possibleDuplicateOfId)
    }

    @Test
    fun `a format 1 backup with an unauthenticated header still restores`() = runTest {
        seed()
        val before = snapshot()

        // Every backup taken before format 2 is a format 1 file. It may be the only
        // copy a shop has.
        val file = ByteArrayOutputStream()
        BackupCrypto.write(
            json = Json.encodeToString(before),
            passphrase = passphrase,
            schemaVersion = BackupRepository.SCHEMA_VERSION,
            output = file,
            random = SecureRandom(),
            formatVersion = 1,
        )
        wipe()

        val result = repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase)

        assertTrue(result.isSuccess)
        assertEquals(before, snapshot())
    }

    @Test
    fun `restoring replaces rather than merges`() = runTest {
        seed()
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)

        // Something happened after the backup was taken.
        db.transactionDao().insert(transaction("t_later", amountMinor = 999, occurredAt = T0))
        assertNotNull(db.transactionDao().getById("t_later"))

        repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase).getOrThrow()

        // The file is a snapshot of everything; merging would produce a ledger that
        // was never anyone's.
        assertEquals(null, db.transactionDao().getById("t_later"))
        assertNotNull(db.transactionDao().getById("t1"))
    }

    @Test
    fun `an empty database backs up and restores as empty`() = runTest {
        val file = ByteArrayOutputStream()
        repository.writeBackup(passphrase, file)

        val result = repository.restore(ByteArrayInputStream(file.toByteArray()), passphrase)

        assertEquals(0, result.getOrThrow().rowsRestored)
    }

    // --- Helpers -----------------------------------------------------------------------

    private suspend fun snapshot() = BackupPayload(
        rawMessages = db.rawMessageDao().getAll(),
        transactions = db.transactionDao().listFiltered(null, null, null, null, null, null, null, null),
        customers = db.customerDao().getAll(),
        creditEntries = db.creditEntryDao().getAll(),
        categories = db.categoryDao().getAll(),
        categoryRules = db.categoryRuleDao().getAll(),
        parserRules = db.parserRuleDao().getAll(),
        reconciliationSessions = db.reconciliationSessionDao().getAll(),
    )

    private suspend fun wipe() {
        db.creditEntryDao().deleteAll()
        db.customerDao().deleteAll()
        db.transactionDao().deleteAll()
        db.rawMessageDao().deleteAll()
        db.categoryRuleDao().deleteAll()
        db.categoryDao().deleteAll()
        db.parserRuleDao().deleteAll()
        db.reconciliationSessionDao().deleteAll()
    }
}
