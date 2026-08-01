package et.alpha.mezgeb.parser.rule

import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.parser.model.Direction
import et.alpha.mezgeb.parser.model.SemanticField
import kotlinx.serialization.Serializable

/**
 * How a rule decides which way the money moved.
 *
 * Keywords are never hard-coded in the engine. A provider that starts sending an
 * Amharic template is then a data change — a new rule in the JSON asset — rather than
 * a code change and an app update.
 */
@Serializable
enum class DirectionStrategy {
    FIXED_IN,
    FIXED_OUT,
    KEYWORD,
}

@Serializable
data class DirectionKeywords(
    val inbound: List<String> = emptyList(),
    val outbound: List<String> = emptyList(),
)

/**
 * A versioned pattern that turns a message into a transaction.
 *
 * [bodyPattern] is a regex with named groups; [fieldMap] says what each group means.
 * Keeping the mapping separate from the regex means a group can be named for
 * readability without the engine having to guess its purpose.
 */
@Serializable
data class ParserRule(
    val id: String,
    val name: String,
    /** Regex matched against the normalised sender. */
    val senderPattern: String,
    val channel: Channel,
    val bodyPattern: String,
    val fieldMap: Map<String, SemanticField>,
    val directionStrategy: DirectionStrategy,
    val directionKeywords: DirectionKeywords? = null,
    val version: Int = 1,
    val isEnabled: Boolean = true,
    /** Higher wins. Equal priority with conflicting results yields Ambiguous. */
    val priority: Int = 0,
    /** Reported on Success so the UI can show how much to trust an extraction. */
    val confidence: Float = 1.0f,
    val notes: String? = null,
) {
    /**
     * Compiled lazily and cached: the engine may test a rule against thousands of
     * backlog messages, and recompiling per message dominates the runtime.
     */
    val senderRegex: Regex by lazy { Regex(senderPattern, RegexOption.IGNORE_CASE) }

    val bodyRegex: Regex by lazy {
        Regex(bodyPattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    fun fixedDirection(): Direction? = when (directionStrategy) {
        DirectionStrategy.FIXED_IN -> Direction.IN
        DirectionStrategy.FIXED_OUT -> Direction.OUT
        DirectionStrategy.KEYWORD -> null
    }
}

/** The bundled rule file's wire format. */
@Serializable
data class RuleSet(
    val formatVersion: Int = 1,
    val rules: List<ParserRule> = emptyList(),
    val notes: String? = null,
)
