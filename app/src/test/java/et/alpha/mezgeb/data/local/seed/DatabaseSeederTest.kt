package et.alpha.mezgeb.data.local.seed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.parserRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DatabaseSeederTest : DatabaseTest() {

    private val clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)

    private fun seeder() = DatabaseSeeder(
        context = ApplicationProvider.getApplicationContext<Context>(),
        categoryDao = db.categoryDao(),
        parserRuleDao = db.parserRuleDao(),
        clock = clock,
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `seeding installs the system categories`() = runTest {
        seeder().seed()

        val categories = db.categoryDao().observeAll().first()
        assertEquals(DatabaseSeeder.SYSTEM_CATEGORIES.size, categories.size)
        assertTrue("every seeded category is a system one", categories.all { it.isSystem })
        assertTrue(categories.any { it.id == "cat_sales" })
    }

    @Test
    fun `seeding twice changes nothing`() = runTest {
        seeder().seed()
        val afterFirst = db.categoryDao().observeAll().first()

        seeder().seed()
        val afterSecond = db.categoryDao().observeAll().first()

        assertEquals(afterFirst, afterSecond)
        assertEquals(DatabaseSeeder.SYSTEM_CATEGORIES.size, db.categoryDao().count())
    }

    @Test
    fun `seeding does not disturb rules the shop taught itself`() = runTest {
        db.parserRuleDao().insert(
            parserRule("taught", isUserCreated = true, bodyPattern = "my own pattern"),
        )

        seeder().seed()

        val stored = db.parserRuleDao().getById("taught")
        assertEquals("my own pattern", stored?.bodyPattern)
        assertEquals(true, stored?.isUserCreated)
    }

    @Test
    fun `the bundled rules asset parses`() = runTest {
        // The asset is copied from :parser by the syncParserRules task, so this also
        // proves the two modules agree on the wire format.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.assets.open(DatabaseSeeder.RULES_ASSET)
            .bufferedReader().use { it.readText() }

        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<BundledRulesFile>(raw)

        assertEquals(1, parsed.formatVersion)
        assertTrue("expected bundled rules, got none", parsed.rules.isNotEmpty())
        assertTrue(
            "every bundled rule must map at least an amount",
            parsed.rules.all { it.fieldMap.values.contains("AMOUNT") },
        )
    }

    @Test
    fun `seeding installs the bundled rules the parser was measured against`() = runTest {
        seeder().seed()

        val stored = db.parserRuleDao().getEnabled()
        assertTrue("no rules were seeded", stored.isNotEmpty())
        assertTrue("bundled rules must not be marked user-created", stored.none { it.isUserCreated })
        // The field map survives the flattening into the entity's string column.
        assertTrue(stored.all { it.fieldMapJson.contains("AMOUNT") })
    }

    @Test
    fun `a malformed rules asset does not throw`() = runTest {
        // The shop keeps a usable ledger; messages just land in the review queue.
        val broken = DatabaseSeeder(
            context = ApplicationProvider.getApplicationContext<Context>(),
            categoryDao = db.categoryDao(),
            parserRuleDao = db.parserRuleDao(),
            clock = clock,
            json = Json { ignoreUnknownKeys = false },
        )

        broken.seed()

        // Categories still seeded even if rules failed.
        assertEquals(DatabaseSeeder.SYSTEM_CATEGORIES.size, db.categoryDao().count())
    }
}
