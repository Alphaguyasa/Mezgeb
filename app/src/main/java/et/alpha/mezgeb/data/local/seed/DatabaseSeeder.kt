package et.alpha.mezgeb.data.local.seed

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.data.local.dao.CategoryDao
import et.alpha.mezgeb.data.local.dao.ParserRuleDao
import et.alpha.mezgeb.data.local.entity.CategoryEntity
import et.alpha.mezgeb.data.local.entity.ParserRuleEntity
import et.alpha.mezgeb.domain.model.CategoryKind
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * First-run seeding, and re-run-on-update seeding for bundled rules.
 *
 * The whole thing is idempotent: running it twice changes nothing. Categories are
 * inserted with `IGNORE` so a user's rename of "Sales" survives; bundled rules are
 * upserted by id so an app update can correct a bad pattern, while rules the shop
 * taught itself (`isUserCreated = true`) are never in the upsert set and so are never
 * touched.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val categoryDao: CategoryDao,
    private val parserRuleDao: ParserRuleDao,
    private val clock: Clock,
    private val json: Json,
) {

    suspend fun seed() {
        seedCategories()
        seedBundledParserRules()
    }

    private suspend fun seedCategories() {
        categoryDao.insertIgnoringExisting(SYSTEM_CATEGORIES)
    }

    private suspend fun seedBundledParserRules() {
        val parsed = runCatching {
            val raw = context.assets.open(RULES_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString<BundledRulesFile>(raw)
        }.getOrElse { error ->
            // A malformed asset must not brick the app on launch: the shop still has a
            // usable ledger with an empty rule set, and every message lands in the
            // review queue where it can be taught by hand.
            SeedFailure.lastError = error
            return
        }

        if (parsed.rules.isEmpty()) return

        val now = clock.millis()
        parserRuleDao.upsertAll(
            parsed.rules.map { rule ->
                ParserRuleEntity(
                    id = rule.id,
                    name = rule.name,
                    senderPattern = rule.senderPattern,
                    channel = rule.channel,
                    bodyPattern = rule.bodyPattern,
                    // The map and keyword object are flattened here: the entity stores
                    // them as strings, the asset carries them structured.
                    fieldMapJson = json.encodeToString(rule.fieldMap),
                    directionStrategy = rule.directionStrategy,
                    directionKeywordsJson = rule.directionKeywords?.let { json.encodeToString(it) },
                    version = rule.version,
                    isUserCreated = false,
                    isEnabled = rule.isEnabled,
                    priority = rule.priority,
                    createdAt = now,
                    // Reset on re-seed. Match counts are local telemetry, not data.
                    matchCount = 0,
                    // Re-asserted on every seed, which is what repairs the rows the
                    // v4 → v5 migration had to default to 1.0.
                    confidence = rule.confidence,
                )
            },
        )
    }

    /** Surfaced in settings as a diagnostic; never logged with message content. */
    object SeedFailure {
        @Volatile
        var lastError: Throwable? = null
    }

    companion object {
        const val RULES_ASSET = "parser_rules.json"

        /**
         * The default chart of accounts for a small shop. Ids are stable strings, not
         * UUIDs, so that a bundled category rule can reference one by id.
         */
        val SYSTEM_CATEGORIES: List<CategoryEntity> = listOf(
            CategoryEntity("cat_sales", "Sales", CategoryKind.INCOME, "#0B6E4F", isSystem = true),
            CategoryEntity("cat_supplier", "Supplier payment", CategoryKind.EXPENSE, "#9E4400", isSystem = true),
            CategoryEntity("cat_rent", "Rent", CategoryKind.EXPENSE, "#3D5A80", isSystem = true),
            CategoryEntity("cat_utilities", "Utilities", CategoryKind.EXPENSE, "#63614F", isSystem = true),
            CategoryEntity("cat_salary", "Salary", CategoryKind.EXPENSE, "#7A4E8C", isSystem = true),
            CategoryEntity("cat_transfer", "Transfer", CategoryKind.EXPENSE, "#00598C", isSystem = true),
            CategoryEntity("cat_other", "Other", CategoryKind.EXPENSE, "#7C7768", isSystem = true),
        )
    }
}
