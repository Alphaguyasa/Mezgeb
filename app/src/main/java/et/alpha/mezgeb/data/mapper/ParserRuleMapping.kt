package et.alpha.mezgeb.data.mapper

import et.alpha.mezgeb.domain.model.ParserRule
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.DirectionKeywords
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import kotlinx.serialization.json.Json
import et.alpha.mezgeb.parser.model.Channel as EngineChannel
import et.alpha.mezgeb.parser.rule.ParserRule as EngineRule

/**
 * Room's flat rule row rehydrated into the engine's rule.
 *
 * The two types stay separate because `:parser` is a pure JVM module that must not
 * know about the app, and because Room stores the field map and the direction
 * keywords as JSON strings while the engine wants them structured.
 *
 * Returns null for a rule that cannot be rehydrated — an unknown semantic field, a
 * direction strategy from a newer build, a regex that will not compile. A rule the
 * shop taught itself in the review screen is user input, and user input must not be
 * able to stop the capture funnel. The message lands in the review queue instead,
 * which is exactly where a rule problem should surface.
 *
 * `confidence` is carried as of schema 5. Before that the entity had no column for it
 * and every rule arrived here reporting 1.0 regardless of what its author declared —
 * a bundled rule saying 0.9 and an induced rule saying 0.8 both claimed certainty.
 */
fun ParserRule.toEngineRule(json: Json): EngineRule? = runCatching {
    // Compiled eagerly, only to find out here whether they compile at all. The engine
    // compiles lazily, which would otherwise turn a bad pattern into an exception
    // thrown mid-capture rather than a rule that is quietly not used.
    Regex(senderPattern)
    Regex(bodyPattern)

    EngineRule(
        id = id,
        name = name,
        senderPattern = senderPattern,
        channel = EngineChannel.valueOf(channel.name),
        bodyPattern = bodyPattern,
        fieldMap = json.decodeFromString<Map<String, String>>(fieldMapJson)
            .mapValues { (_, field) -> SemanticField.valueOf(field) },
        directionStrategy = DirectionStrategy.valueOf(directionStrategy),
        directionKeywords = directionKeywordsJson?.let {
            json.decodeFromString<DirectionKeywords>(it)
        },
        version = version,
        isEnabled = isEnabled,
        priority = priority,
        confidence = confidence,
    )
}.getOrNull()
