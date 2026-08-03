package et.alpha.mezgeb.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import et.alpha.mezgeb.data.local.dao.CategoryDao
import et.alpha.mezgeb.data.local.dao.CategoryRuleDao
import et.alpha.mezgeb.data.local.dao.CreditEntryDao
import et.alpha.mezgeb.data.local.dao.CustomerDao
import et.alpha.mezgeb.data.local.dao.ParserRuleDao
import et.alpha.mezgeb.data.local.dao.RawMessageDao
import et.alpha.mezgeb.data.local.dao.ReconciliationSessionDao
import et.alpha.mezgeb.data.local.dao.TransactionDao
import et.alpha.mezgeb.data.local.entity.CategoryEntity
import et.alpha.mezgeb.data.local.entity.CategoryRuleEntity
import et.alpha.mezgeb.data.local.entity.CreditEntryEntity
import et.alpha.mezgeb.data.local.entity.CustomerEntity
import et.alpha.mezgeb.data.local.entity.ParserRuleEntity
import et.alpha.mezgeb.data.local.entity.RawMessageEntity
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.data.local.entity.TransactionEntity

/**
 * The shop's ledger. One database, `mezgeb.db`, entirely on device.
 *
 * `exportSchema` is on and the generated JSON is committed under `app/schemas/`, so
 * that every future migration can be diffed against the schema it migrates from.
 */
@Database(
    entities = [
        RawMessageEntity::class,
        TransactionEntity::class,
        CustomerEntity::class,
        CreditEntryEntity::class,
        CategoryEntity::class,
        CategoryRuleEntity::class,
        ParserRuleEntity::class,
        ReconciliationSessionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MezgebDatabase : RoomDatabase() {

    abstract fun rawMessageDao(): RawMessageDao
    abstract fun transactionDao(): TransactionDao
    abstract fun customerDao(): CustomerDao
    abstract fun creditEntryDao(): CreditEntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryRuleDao(): CategoryRuleDao
    abstract fun parserRuleDao(): ParserRuleDao
    abstract fun reconciliationSessionDao(): ReconciliationSessionDao

    companion object {
        const val NAME: String = "mezgeb.db"
    }
}
