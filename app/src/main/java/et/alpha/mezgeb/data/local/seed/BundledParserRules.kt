package et.alpha.mezgeb.data.local.seed

import et.alpha.mezgeb.domain.model.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format of `assets/parser_rules.json`.
 *
 * Kept separate from [et.alpha.mezgeb.data.local.entity.ParserRuleEntity] so that the
 * asset format and the database schema can version independently — the asset is
 * authored by hand, the table is migrated by Room.
 */
@Serializable
data class BundledRulesFile(
    val formatVersion: Int,
    val rules: List<BundledParserRule> = emptyList(),
    val comment: String? = null,
)

@Serializable
data class BundledParserRule(
    val id: String,
    val name: String,
    val senderPattern: String,
    val channel: Channel,
    val bodyPattern: String,
    /** Named capture group -> semantic field, serialised as JSON. */
    @SerialName("fieldMap") val fieldMapJson: String,
    val directionStrategy: String,
    @SerialName("directionKeywords") val directionKeywordsJson: String? = null,
    val version: Int = 1,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
)
