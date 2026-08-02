package et.alpha.mezgeb.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import et.alpha.mezgeb.data.local.entity.CategoryEntity
import et.alpha.mezgeb.data.local.entity.CategoryRuleEntity
import et.alpha.mezgeb.data.local.entity.ParserRuleEntity
import et.alpha.mezgeb.data.local.entity.ReconciliationSessionEntity
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(categories: List<CategoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY isSystem DESC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    /** One-shot read, for the form screens that only need the list once. */
    @Query("SELECT * FROM categories ORDER BY isSystem DESC, name ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: CategoryKind): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    /** System categories are structural; only user-created ones may be removed. */
    @Query("DELETE FROM categories WHERE id = :id AND isSystem = 0")
    suspend fun deleteUserCategory(id: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface CategoryRuleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: CategoryRuleEntity)

    @Update
    suspend fun update(rule: CategoryRuleEntity)

    @Query("DELETE FROM category_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM category_rules ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<CategoryRuleEntity>>

    @Query("SELECT * FROM category_rules ORDER BY priority DESC, id ASC")
    suspend fun getAll(): List<CategoryRuleEntity>

    @Query("SELECT * FROM category_rules WHERE categoryId = :categoryId")
    suspend fun getForCategory(categoryId: String): List<CategoryRuleEntity>

    @Query("DELETE FROM category_rules")
    suspend fun deleteAll()
}

@Dao
interface ParserRuleDao {

    /**
     * Bundled rules are replaced by id on every app start; user-taught rules are
     * never touched by that path. Upsert gives exactly that behaviour when the seeder
     * passes only bundled rules.
     */
    @Upsert
    suspend fun upsertAll(rules: List<ParserRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: ParserRuleEntity)

    @Update
    suspend fun update(rule: ParserRuleEntity)

    @Query("DELETE FROM parser_rules WHERE id = :id AND isUserCreated = 1")
    suspend fun deleteUserRule(id: String)

    @Query("SELECT * FROM parser_rules WHERE id = :id")
    suspend fun getById(id: String): ParserRuleEntity?

    @Query("SELECT * FROM parser_rules WHERE isEnabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun getEnabled(): List<ParserRuleEntity>

    @Query("SELECT * FROM parser_rules ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<ParserRuleEntity>>

    @Query("SELECT COUNT(*) FROM parser_rules WHERE isUserCreated = 0")
    suspend fun countBundled(): Int

    @Query("UPDATE parser_rules SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setEnabled(id: String, isEnabled: Boolean)

    @Query("UPDATE parser_rules SET matchCount = matchCount + 1 WHERE id = :id")
    suspend fun incrementMatchCount(id: String)

    @Query("DELETE FROM parser_rules")
    suspend fun deleteAll()
}

@Dao
interface ReconciliationSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ReconciliationSessionEntity)

    @Update
    suspend fun update(session: ReconciliationSessionEntity)

    @Query("SELECT * FROM reconciliation_sessions WHERE id = :id")
    suspend fun getById(id: String): ReconciliationSessionEntity?

    @Query("SELECT * FROM reconciliation_sessions WHERE status = 'OPEN' ORDER BY periodStart DESC LIMIT 1")
    fun observeOpenSession(): Flow<ReconciliationSessionEntity?>

    @Query("SELECT * FROM reconciliation_sessions WHERE status = 'OPEN' ORDER BY periodStart DESC LIMIT 1")
    suspend fun getOpenSession(): ReconciliationSessionEntity?

    @Query("SELECT * FROM reconciliation_sessions WHERE status = :status ORDER BY periodStart DESC")
    fun observeByStatus(status: SessionStatus): Flow<List<ReconciliationSessionEntity>>

    @Query("SELECT * FROM reconciliation_sessions WHERE status = 'CLOSED' ORDER BY periodEnd DESC LIMIT 1")
    suspend fun getMostRecentlyClosed(): ReconciliationSessionEntity?

    @Query("DELETE FROM reconciliation_sessions")
    suspend fun deleteAll()
}
