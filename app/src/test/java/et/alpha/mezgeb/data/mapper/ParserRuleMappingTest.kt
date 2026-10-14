package et.alpha.mezgeb.data.mapper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.data.local.DatabaseTest
import et.alpha.mezgeb.data.local.parserRule
import et.alpha.mezgeb.data.local.seed.DatabaseSeeder
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.model.Direction
import et.alpha.mezgeb.parser.model.ParseOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The whole chain, from the rules file the accuracy harness measures to a parsed
 * amount: shipped asset → seeder → Room row → engine rule → parser.
 *
 * Worth testing as one piece because every link is a place where the rules could
 * quietly stop being used — a renamed enum, a field map that will not decode — and
 * the symptom is identical in each case: every message lands in the review queue and
 * the app looks like it simply cannot parse anything.
 */
class ParserRuleMappingTest : DatabaseTest() {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the bundled rules survive the round trip through Room`() = runTest {
        val engineRules = seedAndMap()

        assertTrue("no bundled rules were rehydrated", engineRules.isNotEmpty())

        val outcome = MessageParser(engineRules).parse(
            sender = "CBE",
            body = "Dear Customer your Account 1*******0000 has been credited with ETB " +
                "450.00. Your Current Balance is ETB 1,200.00. Thanks for Banking with CBE.",
            receivedAt = RECEIVED_AT,
        )

        val success = outcome as ParseOutcome.Success
        assertEquals(45_000L, success.fields.amountMinor)
        assertEquals(120_000L, success.fields.balanceAfterMinor)
        assertEquals(Direction.IN, success.fields.direction)
    }

    @Test
    fun `confidence reaches the engine instead of being flattened to certainty`() = runTest {
        db.parserRuleDao().insert(parserRule("induced", confidence = 0.8f))

        val rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
            .getEnabledParserRules()
            .mapNotNull { it.toEngineRule(json) }

        // The whole point of persisting it. A rule generalised from one tagged message
        // has to arrive at the engine still saying so.
        assertEquals(0.8f, rules.single { it.id == "induced" }.confidence)
    }

    @Test
    fun `a rule that cannot be rehydrated is dropped, not thrown`() = runTest {
        // A rule the shop taught itself is user input. A pattern that will not compile
        // must cost that one rule, not the entire capture funnel.
        db.parserRuleDao().insert(parserRule("broken", bodyPattern = "(unclosed"))

        val rules = RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
            .getEnabledParserRules()

        assertNull(rules.single { it.id == "broken" }.toEngineRule(json))
    }

    private suspend fun seedAndMap() = run {
        DatabaseSeeder(
            context = ApplicationProvider.getApplicationContext<Context>(),
            categoryDao = db.categoryDao(),
            parserRuleDao = db.parserRuleDao(),
            clock = Clock.fixed(Instant.ofEpochMilli(RECEIVED_AT), ZoneOffset.UTC),
            json = json,
        ).seed()

        RuleRepository(db.categoryDao(), db.categoryRuleDao(), db.parserRuleDao())
            .getEnabledParserRules()
            .mapNotNull { it.toEngineRule(json) }
    }

    private companion object {
        const val RECEIVED_AT = 1_785_000_000_000L
    }
}
