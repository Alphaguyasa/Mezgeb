package et.alpha.mezgeb.ui.rule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.capture.BacklogReprocessor
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.data.mapper.toEngineRule
import et.alpha.mezgeb.data.repo.RuleRepository
import et.alpha.mezgeb.data.repo.TransactionRepository
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.ParserRule
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.navigation.MezgebDestination
import et.alpha.mezgeb.parser.induce.InductionCandidate
import et.alpha.mezgeb.parser.induce.InductionFailure
import et.alpha.mezgeb.parser.induce.InductionOutcome
import et.alpha.mezgeb.parser.induce.InductionReport
import et.alpha.mezgeb.parser.induce.RuleInducer
import et.alpha.mezgeb.parser.induce.TaggedSpan
import et.alpha.mezgeb.parser.model.ParseOutcome
import et.alpha.mezgeb.parser.model.ParsedFields
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.parser.rule.SenderAllowlist
import et.alpha.mezgeb.util.IdGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import et.alpha.mezgeb.parser.MessageParser as EngineParser
import et.alpha.mezgeb.parser.rule.ParserRule as EngineRule

/** The fields a user can point at. Deliberately four, not seven. */
val TAGGABLE_FIELDS: List<SemanticField> = listOf(
    SemanticField.AMOUNT,
    SemanticField.COUNTERPARTY_NAME,
    SemanticField.PROVIDER_TXN_ID,
    SemanticField.BALANCE_AFTER,
)

data class RuleBuilderUiState(
    val isLoading: Boolean = true,
    val sender: String = "",
    val messages: List<RawMessage> = emptyList(),
    val exampleIndex: Int = 0,
    /** Character ranges into the example body, by field. */
    val spans: Map<SemanticField, IntRange> = emptyMap(),
    val direction: Direction = Direction.IN,
    val channel: Channel = Channel.BANK,
    /** What the transaction would look like. Recomputed on every tag. */
    val preview: ParsedFields? = null,
    val inductionFailure: InductionFailure? = null,
    val report: InductionReport? = null,
    val isSaving: Boolean = false,
    val savedRuleResolved: Int? = null,
    val resolvedManually: Boolean = false,
    val errorMessage: ErrorKind? = null,
) {
    val example: RawMessage? get() = messages.getOrNull(exampleIndex)

    /** Amount is the one field a transaction cannot exist without. */
    val hasAmount: Boolean get() = spans.containsKey(SemanticField.AMOUNT)

    val canSave: Boolean get() = hasAmount && preview != null && !isSaving

    val otherMessageCount: Int get() = (messages.size - 1).coerceAtLeast(0)

    enum class ErrorKind { SAVE_FAILED }
}

sealed interface RuleBuilderEvent {
    /** The user selected text and tapped a field chip. */
    data class TagSpan(val field: SemanticField, val range: IntRange) : RuleBuilderEvent

    data class ClearSpan(val field: SemanticField) : RuleBuilderEvent
    data class ExampleChanged(val index: Int) : RuleBuilderEvent
    data class DirectionChanged(val direction: Direction) : RuleBuilderEvent
    data class ChannelChanged(val channel: Channel) : RuleBuilderEvent

    /** Run the candidate rule against this sender's other waiting messages. */
    data object TestRule : RuleBuilderEvent

    data object SaveRule : RuleBuilderEvent

    /** Record this one message and move on, without saving a rule. */
    data object ResolveManually : RuleBuilderEvent

    data object ErrorShown : RuleBuilderEvent
}

/**
 * Teaching the parser a template, by pointing at it.
 *
 * The whole screen exists to make one claim checkable before it is believed: after
 * tagging, the user sees what the rule would extract from *this* message, and then
 * how many of the sender's other waiting messages it reads and what it pulls out of
 * three of them. Only then is there a Save button worth pressing.
 */
@HiltViewModel
class RuleBuilderViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val rules: RuleRepository,
    private val coordinator: CaptureCoordinator,
    private val reprocessor: BacklogReprocessor,
    private val ids: IdGenerator,
    private val json: Json,
    private val clock: Clock,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<MezgebDestination.RuleBuilder>()
    private val inducer = RuleInducer()

    private val _state = MutableStateFlow(RuleBuilderUiState(sender = route.sender))
    val state: StateFlow<RuleBuilderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val waiting = transactions.getUnmatchedBySender(route.sender)
            // When the user came in from one specific message, teach from that one.
            val index = route.rawMessageId
                ?.let { id -> waiting.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 }
                ?: 0
            _state.update {
                it.copy(isLoading = false, messages = waiting, exampleIndex = index)
            }
        }
    }

    fun onEvent(event: RuleBuilderEvent) {
        when (event) {
            is RuleBuilderEvent.TagSpan -> update {
                // Re-tagging a field replaces it. There is no "add a second amount".
                it.copy(spans = it.spans + (event.field to event.range), report = null)
            }

            is RuleBuilderEvent.ClearSpan -> update {
                it.copy(spans = it.spans - event.field, report = null)
            }

            is RuleBuilderEvent.ExampleChanged -> update {
                // Offsets belong to the message they were drawn on.
                it.copy(exampleIndex = event.index, spans = emptyMap(), report = null)
            }

            is RuleBuilderEvent.DirectionChanged -> update { it.copy(direction = event.direction) }
            is RuleBuilderEvent.ChannelChanged -> update { it.copy(channel = event.channel) }

            RuleBuilderEvent.TestRule -> testRule()
            RuleBuilderEvent.SaveRule -> saveRule()
            RuleBuilderEvent.ResolveManually -> resolveManually()
            RuleBuilderEvent.ErrorShown -> _state.update { it.copy(errorMessage = null) }
        }
    }

    /** Every state change re-derives the preview, so it can never be stale. */
    private inline fun update(transform: (RuleBuilderUiState) -> RuleBuilderUiState) {
        _state.update { current ->
            val next = transform(current)
            val outcome = next.induce()
            next.copy(
                preview = (outcome as? InductionOutcome.Success)?.let { next.previewWith(it) },
                inductionFailure = (outcome as? InductionOutcome.Failed)?.reason,
            )
        }
    }

    private fun RuleBuilderUiState.induce(): InductionOutcome? {
        val body = example?.body ?: return null
        if (spans.isEmpty()) return null
        return inducer.induce(
            sender = sender,
            body = body,
            spans = spans.map { (field, range) -> TaggedSpan(field, range.first, range.last + 1) },
            channel = et.alpha.mezgeb.parser.model.Channel.valueOf(channel.name),
            direction = et.alpha.mezgeb.parser.model.Direction.valueOf(direction.name),
            ruleId = PREVIEW_RULE_ID,
        )
    }

    private fun RuleBuilderUiState.previewWith(outcome: InductionOutcome.Success): ParsedFields? {
        val message = example ?: return null
        val parser = EngineParser(
            rules = listOf(outcome.induced.rule),
            allowlist = SenderAllowlist(listOf(outcome.induced.rule.senderPattern)),
        )
        val parsed = parser.parse(message.sender, message.body, message.receivedAt)
        return (parsed as? ParseOutcome.Success)?.fields
    }

    private fun testRule() {
        val current = _state.value
        val outcome = current.induce() as? InductionOutcome.Success ?: return

        viewModelScope.launch {
            val others = current.messages.filterIndexed { index, _ -> index != current.exampleIndex }
            val existing = rules.getEnabledParserRules().mapNotNull { it.toEngineRule(json) }

            val report = inducer.evaluate(
                rule = outcome.induced.rule,
                candidates = others.map {
                    InductionCandidate(it.id, it.sender, it.body, it.receivedAt)
                },
                existingRules = existing,
            )
            _state.update { it.copy(report = report) }
        }
    }

    private fun saveRule() {
        val current = _state.value
        val outcome = current.induce() as? InductionOutcome.Success ?: return
        if (!current.canSave) return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = runCatching {
                rules.insertParserRule(outcome.induced.rule.toDomainRule(current))
            }

            if (result.isFailure) {
                _state.update {
                    it.copy(isSaving = false, errorMessage = RuleBuilderUiState.ErrorKind.SAVE_FAILED)
                }
                return@launch
            }

            // The rule is data, so it applies to what is already waiting. Doing this
            // is the difference between teaching one message and clearing a backlog.
            val summary = coordinator.reparseUnmatched()
            // Anything left over — thousands of messages after an inbox import — runs
            // on in the background where the user does not have to wait for it.
            reprocessor.start()

            _state.update {
                it.copy(isSaving = false, savedRuleResolved = summary.recorded)
            }
        }
    }

    private fun resolveManually() {
        val current = _state.value
        val message = current.example ?: return
        val fields = current.preview ?: return

        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val result = runCatching { coordinator.resolveManually(message.id, fields) }
            _state.update {
                if (result.isSuccess) {
                    it.copy(isSaving = false, resolvedManually = true)
                } else {
                    it.copy(isSaving = false, errorMessage = RuleBuilderUiState.ErrorKind.SAVE_FAILED)
                }
            }
        }
    }

    private fun EngineRule.toDomainRule(state: RuleBuilderUiState): ParserRule = ParserRule(
        id = ids.newId(),
        name = "${state.sender} — taught ${state.direction.name.lowercase()}",
        senderPattern = senderPattern,
        channel = state.channel,
        bodyPattern = bodyPattern,
        fieldMapJson = json.encodeToString(fieldMap.mapValues { it.value.name }),
        directionStrategy = directionStrategy.name,
        directionKeywordsJson = null,
        version = 1,
        // Never touched by an app update, unlike the bundled rules.
        isUserCreated = true,
        isEnabled = true,
        priority = priority,
        createdAt = clock.millis(),
        matchCount = 0,
    )

    private companion object {
        const val PREVIEW_RULE_ID = "preview"
    }
}
