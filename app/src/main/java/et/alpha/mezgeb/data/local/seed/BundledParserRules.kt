package et.alpha.mezgeb.data.local.seed

import et.alpha.mezgeb.domain.model.Channel
import kotlinx.serialization.Serializable

/**
 * Wire format of `assets/parser_rules.json`.
 *
 * This mirrors `et.alpha.mezgeb.parser.rule.RuleSet` field for field. The two are
 * deliberately separate types rather than a shared one: `:parser` is a pure JVM module
 * and must not depend on the app, and the app stores rules in Room where the field map
 * is a serialised string rather than a map. The asset itself is copied from `:parser`
 * at build time by the `syncParserRules` task, so there is exactly one file.
 */
@Serializable
data class BundledRulesFile(
    val formatVersion: Int,
    val rules: List<BundledParserRule> = emptyList(),
    val notes: String? = null,
)

@Serializable
data class BundledParserRule(
    val id: String,
    val name: String,
    val senderPattern: String,
    val channel: Channel,
    val bodyPattern: String,
    /** Named capture group -> semantic field. Flattened to JSON for storage. */
    val fieldMap: Map<String, String> = emptyMap(),
    val directionStrategy: String,
    val directionKeywords: BundledDirectionKeywords? = null,
    val version: Int = 1,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val confidence: Float = 1.0f,
    val notes: String? = null,
)

@Serializable
data class BundledDirectionKeywords(
    val inbound: List<String> = emptyList(),
    val outbound: List<String> = emptyList(),
)
