package et.alpha.mezgeb.capture

import et.alpha.mezgeb.data.mapper.toEngineRule
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.di.ApplicationScope
import et.alpha.mezgeb.parser.MessageParser
import et.alpha.mezgeb.parser.rule.SenderAllowlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out a [MessageParser] built from whatever rules are currently in the
 * database.
 *
 * Exists as an interface because the capture funnel's tests care about what the
 * parser concluded, not about how the rules were stored.
 */
interface ParserProvider {
    /** Suspends until rules have been read at least once. */
    suspend fun current(): MessageParser
}

/**
 * Rebuilds the parser whenever the rule table changes, and caches it in between.
 *
 * Both halves matter. Caching: a rule's regexes are compiled lazily and cached on
 * the rule object, so constructing a parser per message would recompile every
 * pattern thousands of times during an inbox backfill. Rebuilding: a rule the shop
 * teaches in the review screen has to take effect on the next message, not on the
 * next app launch.
 *
 * The allowlist stays [SenderAllowlist.DEFAULT] rather than being narrowed to the
 * senders that have rules. A financial sender nobody has written a rule for yet
 * should reach the review queue, which is where the shop teaches one; narrowing the
 * allowlist to existing rules would quietly discard exactly the messages the app
 * most needs to be shown.
 */
@Singleton
class RuleBackedParserProvider @Inject constructor(
    ruleRepository: RuleRepository,
    json: Json,
    @ApplicationScope scope: CoroutineScope,
) : ParserProvider {

    private val parser: StateFlow<MessageParser?> = ruleRepository.observeParserRules()
        .map { rules -> MessageParser(rules.mapNotNull { it.toEngineRule(json) }) }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = null)

    override suspend fun current(): MessageParser = parser.filterNotNull().first()
}
