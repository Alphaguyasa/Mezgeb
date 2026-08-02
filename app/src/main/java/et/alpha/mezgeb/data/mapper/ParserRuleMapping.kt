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
 * `confidence` is not carried: the entity has no column for it, so every rule
 * reports 1.0 here even when the bundled JSON says otherwise. Noted in NOTES.md;
 * it needs a schema change and Session 6 is already opening that file.
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
    )
}.getOrNull()
