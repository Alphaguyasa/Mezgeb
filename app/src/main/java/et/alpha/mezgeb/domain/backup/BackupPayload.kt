package et.alpha.mezgeb.domain.backup

import et.alpha.mezgeb.data.local.entity.CategoryEntity
import et.alpha.mezgeb.data.local.entity.CategoryRuleEntity
import et.alpha.mezgeb.data.local.entity.CreditEntryEntity
import et.alpha.mezgeb.data.local.entity.CustomerEntity
import et.alpha.mezgeb.data.local.entity.ParserRuleEntity
import et.alpha.mezgeb.data.local.entity.RawMessageEntity
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.data.local.entity.TransactionEntity
import kotlinx.serialization.Serializable

/**
 * The whole database, as JSON, ready to be encrypted.
 *
 * Entities are serialised directly rather than through the domain models. A backup
 * has to restore what was actually stored, including the storage-shaped fields the
 * domain layer deliberately hides — and a round trip through a model that drops a
 * column would silently lose it.
 *
 * Insertion order matters on restore: customers before their credit entries, raw
 * messages before the transactions that reference them, or the foreign keys reject
 * the lot.
 */
@Serializable
data class BackupPayload(
    val rawMessages: List<RawMessageEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val customers: List<CustomerEntity> = emptyList(),
    val creditEntries: List<CreditEntryEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val categoryRules: List<CategoryRuleEntity> = emptyList(),
    val parserRules: List<ParserRuleEntity> = emptyList(),
    val reconciliationSessions: List<ReconciliationSessionEntity> = emptyList(),
) {
    val rowCount: Int
        get() = rawMessages.size + transactions.size + customers.size + creditEntries.size +
            categories.size + categoryRules.size + parserRules.size + reconciliationSessions.size
}
