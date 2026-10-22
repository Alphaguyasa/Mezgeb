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
        json = lenientJson,
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

        val parsed = lenientJson.decodeFromString<BundledRulesFile>(raw)

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
    fun `a bundled rule keeps the confidence its author declared`() = runTest {
        seeder().seed()

        val stored = db.parserRuleDao().getEnabled()
        // The asset declares 0.9. Before schema 5 there was no column to put it in, so
        // every rule came back claiming 1.0 — a rule stating its own uncertainty and a
        // rule stating certainty were indistinguishable once stored.
        assertTrue(
            "a bundled rule that declares less than full confidence must keep it",
            stored.any { it.confidence < 1.0f },
        )
        assertTrue("confidence must be a probability", stored.all { it.confidence in 0f..1f })
    }

    @Test
    fun `re-seeding repairs a confidence the migration had to default`() = runTest {
        // Exactly the v4 -> v5 case: rows that existed before the column did were
        // defaulted to 1.0, because their real figure was not recoverable from the row.
        // For bundled rules it is recoverable — it is in the asset — and the seeder is
        // what puts it back.
        seeder().seed()
        val id = db.parserRuleDao().getEnabled().first { it.confidence < 1.0f }.id
        db.parserRuleDao().upsertAll(
            listOf(db.parserRuleDao().getById(id)!!.copy(confidence = 1.0f)),
        )
        assertEquals(1.0f, db.parserRuleDao().getById(id)?.confidence)

        seeder().seed()

        assertTrue(
            "re-seeding must restore the asset's figure, not leave the default",
            db.parserRuleDao().getById(id)!!.confidence < 1.0f,
        )
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

    private companion object {
        /** One instance for the class: kotlinx.serialization warns when one is built per use. */
        val lenientJson = Json { ignoreUnknownKeys = true }
    }
}
