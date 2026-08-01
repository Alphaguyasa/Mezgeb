package et.alpha.mezgeb.parser.rule

import kotlinx.serialization.json.Json

/**
 * Reads the bundled rule set.
 *
 * The rules are data, not code: the same JSON is read by `:parser`'s accuracy harness
 * and shipped as an app asset, so what the tests measure is exactly what the app runs.
 */
object RuleSetLoader {

    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = false
    }

    fun fromJson(text: String): RuleSet = json.decodeFromString(text)

    fun toJson(ruleSet: RuleSet): String = json.encodeToString(ruleSet)

    /** Loads from the classpath, e.g. `parser_rules.json` in resources. */
    fun fromResource(name: String): RuleSet {
        val stream = RuleSetLoader::class.java.classLoader?.getResourceAsStream(name)
            ?: error("rule resource '$name' not found on the classpath")
        return stream.bufferedReader().use { fromJson(it.readText()) }
    }
}
