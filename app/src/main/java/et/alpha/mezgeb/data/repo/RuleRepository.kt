package et.alpha.mezgeb.data.repo

import et.alpha.mezgeb.data.local.dao.CategoryDao
import et.alpha.mezgeb.data.local.dao.CategoryRuleDao
import et.alpha.mezgeb.data.local.dao.ParserRuleDao
import et.alpha.mezgeb.data.mapper.toDomain
import et.alpha.mezgeb.data.mapper.toEntity
import et.alpha.mezgeb.domain.model.Category
import et.alpha.mezgeb.domain.model.CategoryKind
import et.alpha.mezgeb.domain.model.CategoryRule
import et.alpha.mezgeb.domain.model.ParserRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Categories, category rules and parser rules — everything the app matches against. */
@Singleton
class RuleRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val categoryRuleDao: CategoryRuleDao,
    private val parserRuleDao: ParserRuleDao,
) {

    // --- Categories ---------------------------------------------------------------

    fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeCategories(kind: CategoryKind): Flow<List<Category>> =
        categoryDao.observeByKind(kind).map { list -> list.map { it.toDomain() } }

    suspend fun getCategories(): List<Category> = categoryDao.getAll().map { it.toDomain() }

    suspend fun getCategory(id: String): Category? = categoryDao.getById(id)?.toDomain()

    suspend fun insertCategory(category: Category) = categoryDao.insert(category.toEntity())

    suspend fun updateCategory(category: Category) = categoryDao.update(category.toEntity())

    /** System categories are structural and cannot be deleted. */
    suspend fun deleteUserCategory(id: String) = categoryDao.deleteUserCategory(id)

    // --- Category rules -----------------------------------------------------------

    fun observeCategoryRules(): Flow<List<CategoryRule>> =
        categoryRuleDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getCategoryRules(): List<CategoryRule> =
        categoryRuleDao.getAll().map { it.toDomain() }

    suspend fun insertCategoryRule(rule: CategoryRule) = categoryRuleDao.insert(rule.toEntity())

    suspend fun updateCategoryRule(rule: CategoryRule) = categoryRuleDao.update(rule.toEntity())

    suspend fun deleteCategoryRule(id: String) = categoryRuleDao.delete(id)

    // --- Parser rules -------------------------------------------------------------

    fun observeParserRules(): Flow<List<ParserRule>> =
        parserRuleDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Priority-ordered, enabled rules only. This is what the parser is handed. */
    suspend fun getEnabledParserRules(): List<ParserRule> =
        parserRuleDao.getEnabled().map { it.toDomain() }

    suspend fun getParserRule(id: String): ParserRule? = parserRuleDao.getById(id)?.toDomain()

    suspend fun insertParserRule(rule: ParserRule) = parserRuleDao.insert(rule.toEntity())

    suspend fun updateParserRule(rule: ParserRule) = parserRuleDao.update(rule.toEntity())

    suspend fun setParserRuleEnabled(id: String, isEnabled: Boolean) =
        parserRuleDao.setEnabled(id, isEnabled)

    suspend fun deleteUserParserRule(id: String) = parserRuleDao.deleteUserRule(id)

    suspend fun recordParserRuleMatch(id: String) = parserRuleDao.incrementMatchCount(id)
}
