package et.alpha.mezgeb.domain.backup

import androidx.room.withTransaction
import et.alpha.mezgeb.data.local.MezgebDatabase
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What a restore did, once it decided to go ahead. */
data class RestoreSummary(val rowsRestored: Int, val schemaVersion: Int)

/**
 * Reading the whole database out, and putting a whole database back.
 *
 * Restore is destructive by definition — the file is a snapshot of everything, so
 * merging it would produce a ledger that was never anyone's. The screen asks for
 * explicit confirmation, and everything here happens inside one transaction: a
 * restore that fails halfway leaves the shop with the ledger it had, not with half of
 * two.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val database: MezgebDatabase,
    private val json: Json,
) {

    suspend fun writeBackup(passphrase: CharArray, output: OutputStream) {
        val payload = BackupPayload(
            rawMessages = database.rawMessageDao().getAll(),
            transactions = database.transactionDao().listFiltered(
                from = null, to = null, channel = null, direction = null,
                categoryId = null, isReconciled = null, query = null,
            ),
            customers = database.customerDao().getAll(),
            creditEntries = database.creditEntryDao().getAll(),
            categories = database.categoryDao().getAll(),
            categoryRules = database.categoryRuleDao().getAll(),
            parserRules = database.parserRuleDao().getAll(),
            reconciliationSessions = database.reconciliationSessionDao().getAll(),
        )

        BackupCrypto.write(
            json = json.encodeToString(payload),
            passphrase = passphrase,
            schemaVersion = SCHEMA_VERSION,
            output = output,
        )
    }

    /**
     * Validates the file completely before touching anything.
     *
     * The header check, the schema check and the decryption all happen first, so a
     * bad passphrase or a backup from a newer build costs the user nothing. Only once
     * the payload is in hand does the database get cleared.
     */
    suspend fun restore(input: InputStream, passphrase: CharArray): Result<RestoreSummary> {
        val read = BackupCrypto.read(input, passphrase, maxSchemaVersion = SCHEMA_VERSION)
        val decrypted = when (read) {
            is RestoreResult.Failed -> return Result.failure(RestoreException(read.reason))
            is RestoreResult.Success -> read
        }

        val payload = runCatching { json.decodeFromString<BackupPayload>(decrypted.json) }
            .getOrElse { return Result.failure(RestoreException(RestoreFailure.MALFORMED)) }

        return runCatching {
            database.withTransaction {
                clearAll()

                // Order is not cosmetic: raw messages before the transactions that
                // point at them, customers before their credit entries. The foreign
                // keys reject the lot otherwise.
                database.rawMessageDao().insertAllIgnoringDuplicates(payload.rawMessages)
                database.transactionDao().insertAll(payload.transactions)
                payload.customers.forEach { database.customerDao().insert(it) }
                database.creditEntryDao().insertAll(payload.creditEntries)
                database.categoryDao().insertIgnoringExisting(payload.categories)
                payload.categoryRules.forEach { database.categoryRuleDao().insert(it) }
                database.parserRuleDao().upsertAll(payload.parserRules)
                payload.reconciliationSessions.forEach {
                    database.reconciliationSessionDao().insert(it)
                }
            }
            RestoreSummary(payload.rowCount, decrypted.schemaVersion)
        }
    }

    /** Children before parents, so cascades never surprise anyone mid-wipe. */
    private suspend fun clearAll() {
        database.creditEntryDao().deleteAll()
        database.customerDao().deleteAll()
        database.transactionDao().deleteAll()
        database.rawMessageDao().deleteAll()
        database.categoryRuleDao().deleteAll()
        database.categoryDao().deleteAll()
        database.parserRuleDao().deleteAll()
        database.reconciliationSessionDao().deleteAll()
    }

    companion object {
        /**
         * Must track `MezgebDatabase`'s version. A backup carries this so a future
         * build can decide whether it understands the file — and, eventually, migrate
         * it. Bump both together.
         */
        const val SCHEMA_VERSION = 4
    }
}

/** Carries the reason so the UI can say something specific rather than "failed". */
class RestoreException(val reason: RestoreFailure) : Exception(reason.name)
