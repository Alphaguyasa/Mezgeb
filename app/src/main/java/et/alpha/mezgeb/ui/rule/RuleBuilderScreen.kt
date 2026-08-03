package et.alpha.mezgeb.ui.rule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.designsystem.component.MoneyText
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.RawMessage
import et.alpha.mezgeb.parser.model.SemanticField
import et.alpha.mezgeb.util.MoneyFormatter
import et.alpha.mezgeb.domain.model.Direction as DomainDirection

@Composable
fun RuleBuilderRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RuleBuilderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Both endings leave nothing more to do on this screen.
    LaunchedEffect(state.savedRuleResolved, state.resolvedManually) {
        if (state.savedRuleResolved != null || state.resolvedManually) onBack()
    }

    RuleBuilderScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Teach a rule by pointing at the message.
 *
 * The interaction has to be obvious without instruction, so it is the shortest one
 * that can work: select some text, tap what it is. The message itself is the control
 * — there is no form to fill in describing a message that is right there on screen.
 */
@Composable
fun RuleBuilderScreen(
    state: RuleBuilderUiState,
    onEvent: (RuleBuilderEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.rule_save_failed)

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) {
            snackbarHostState.showSnackbar(failedText)
            onEvent(RuleBuilderEvent.ErrorShown)
        }
    }

    MezgebScaffold(
        title = stringResource(R.string.rule_title, state.sender),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
        snackbarHostState = snackbarHostState,
    ) { inner ->
        val example = state.example
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            if (example == null) {
                if (!state.isLoading) Text(stringResource(R.string.rule_nothing_to_teach))
                return@Column
            }

            Text(
                text = stringResource(R.string.rule_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            MessageWithSpans(state = state, message = example, onEvent = onEvent)

            FieldChips(state = state, onEvent = onEvent)

            DirectionChips(state = state, onEvent = onEvent)

            PreviewCard(state = state)

            state.report?.let { ReportCard(report = it, total = state.otherMessageCount) }

            if (state.messages.size > 1) {
                ExamplePager(state = state, onEvent = onEvent)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedButton(
                    onClick = { onEvent(RuleBuilderEvent.TestRule) },
                    enabled = state.canSave && state.otherMessageCount > 0,
                    modifier = Modifier.heightIn(min = spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.rule_test))
                }
                Button(
                    onClick = { onEvent(RuleBuilderEvent.SaveRule) },
                    enabled = state.canSave,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = spacing.touchTarget)
                        .testTag(TAG_SAVE_RULE),
                ) {
                    Text(stringResource(R.string.rule_save))
                }
            }

            TextButton(
                onClick = { onEvent(RuleBuilderEvent.ResolveManually) },
                enabled = state.canSave,
                modifier = Modifier.heightIn(min = spacing.touchTarget),
            ) {
                Text(stringResource(R.string.rule_resolve_one))
            }
        }
    }
}

/**
 * The message, selectable, with tagged spans highlighted in the colour of their chip.
 *
 * A read-only [BasicTextField] rather than a `Text`: Compose gives no way to read a
 * selection out of a `SelectionContainer`, and the selection is the entire input to
 * this screen.
 */
@Composable
private fun MessageWithSpans(
    state: RuleBuilderUiState,
    message: RawMessage,
    onEvent: (RuleBuilderEvent) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    val colors = fieldColors()

    var value by remember(message.id) { mutableStateOf(TextFieldValue(message.body)) }
    // Switching examples must not carry a selection into different text.
    LaunchedEffect(message.id) { value = TextFieldValue(message.body) }

    BasicTextField(
        value = value,
        onValueChange = { updated ->
            // Read-only: the text never changes, only where the user is pointing.
            value = updated.copy(text = message.body)
        },
        readOnly = true,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = SpanHighlightTransformation(state.spans, colors),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(spacing.md)
            .testTag(TAG_MESSAGE_BODY),
    )

    val selection = value.selection
    if (!selection.collapsed) {
        SelectionChips(
            selectedRange = IntRange(selection.min, selection.max - 1),
            state = state,
            onEvent = onEvent,
        )
    }
}

/** The chips that appear once something is selected. This is the whole interaction. */
@Composable
private fun SelectionChips(
    selectedRange: IntRange,
    state: RuleBuilderUiState,
    onEvent: (RuleBuilderEvent) -> Unit,
) {
    val spacing = MezgebTheme.spacing
    val colors = fieldColors()
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(R.string.rule_what_is_this),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            TAGGABLE_FIELDS.forEach { field ->
                FilterChip(
                    selected = false,
                    onClick = { onEvent(RuleBuilderEvent.TagSpan(field, selectedRange)) },
                    label = { Text(stringResource(field.labelRes())) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        containerColor = colors.getValue(field),
                    ),
                    modifier = Modifier.testTag(tagChipFor(field)),
                )
            }
        }
    }
}

/** What has been tagged so far, and how to untag it. */
@Composable
private fun FieldChips(state: RuleBuilderUiState, onEvent: (RuleBuilderEvent) -> Unit) {
    val spacing = MezgebTheme.spacing
    val colors = fieldColors()
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        TAGGABLE_FIELDS.filter { state.spans.containsKey(it) }.forEach { field ->
            FilterChip(
                selected = true,
                onClick = { onEvent(RuleBuilderEvent.ClearSpan(field)) },
                label = { Text(stringResource(field.labelRes())) },
                leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.rule_clear_field),
                    )
                },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.getValue(field),
                ),
            )
        }
    }
    if (!state.hasAmount) {
        Text(
            text = stringResource(R.string.rule_amount_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DirectionChips(state: RuleBuilderUiState, onEvent: (RuleBuilderEvent) -> Unit) {
    val spacing = MezgebTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(
            text = stringResource(R.string.rule_direction_question),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            DomainDirection.entries.forEach { direction ->
                FilterChip(
                    selected = state.direction == direction,
                    onClick = { onEvent(RuleBuilderEvent.DirectionChanged(direction)) },
                    label = {
                        Text(
                            stringResource(
                                when (direction) {
                                    Direction.IN -> R.string.direction_in
                                    Direction.OUT -> R.string.direction_out
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** What the ledger would show. The claim, before it is believed. */
@Composable
private fun PreviewCard(state: RuleBuilderUiState) {
    val spacing = MezgebTheme.spacing
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.rule_preview_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val preview = state.preview
            if (preview == null) {
                Text(
                    text = stringResource(R.string.rule_preview_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                MoneyText(
                    amountMinor = preview.amountMinor,
                    direction = DomainDirection.valueOf(preview.direction.name),
                    style = MaterialTheme.typography.headlineSmall,
                    showCurrency = true,
                )
                preview.counterpartyName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                preview.providerTxnId?.let {
                    Text(
                        text = stringResource(R.string.detail_field_reference) + ": " + it,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                preview.balanceAfterMinor?.let {
                    Text(
                        text = stringResource(R.string.detail_field_balance) + ": " +
                            MoneyFormatter.format(it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** "Matches 14 of 16", plus three extractions the user can check by eye. */
@Composable
private fun ReportCard(
    report: et.alpha.mezgeb.parser.induce.InductionReport,
    total: Int,
) {
    val spacing = MezgebTheme.spacing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                // Quantified on the total, which is the number "messages" agrees with.
                text = pluralStringResource(
                    R.plurals.rule_report_matches,
                    total,
                    report.matched,
                    total,
                ),
                style = MaterialTheme.typography.titleSmall,
            )

            if (report.looksTooSpecific) {
                Text(
                    text = stringResource(R.string.rule_report_too_specific),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (report.conflicts.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.rule_report_conflict,
                        report.conflicts.first().name,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            report.samples.forEach { sample ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    MoneyText(
                        amountMinor = sample.fields.amountMinor,
                        direction = DomainDirection.valueOf(sample.fields.direction.name),
                        style = MaterialTheme.typography.bodyMedium,
                        showIcon = false,
                    )
                    Text(
                        text = sample.fields.counterpartyName ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamplePager(state: RuleBuilderUiState, onEvent: (RuleBuilderEvent) -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MezgebTheme.spacing.sm),
    ) {
        IconButton(
            onClick = { onEvent(RuleBuilderEvent.ExampleChanged(state.exampleIndex - 1)) },
            enabled = state.exampleIndex > 0,
        ) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = stringResource(R.string.rule_previous_example),
            )
        }
        Text(
            text = stringResource(
                R.string.rule_example_position,
                state.exampleIndex + 1,
                state.messages.size,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        IconButton(
            onClick = { onEvent(RuleBuilderEvent.ExampleChanged(state.exampleIndex + 1)) },
            enabled = state.exampleIndex < state.messages.lastIndex,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.rule_next_example),
            )
        }
    }
}

/**
 * Paints tagged spans, leaving offsets untouched.
 *
 * [OffsetMapping.Identity] is load-bearing: the transformation must not change the
 * length of the text, or every selection offset the user produces afterwards would
 * point at the wrong character.
 */
private class SpanHighlightTransformation(
    private val spans: Map<SemanticField, IntRange>,
    private val colors: Map<SemanticField, Color>,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = buildAnnotatedString {
            append(text.text)
            spans.forEach { (field, range) ->
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (start < end) {
                    addStyle(SpanStyle(background = colors[field] ?: Color.Unspecified), start, end)
                }
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

@Composable
private fun fieldColors(): Map<SemanticField, Color> = mapOf(
    SemanticField.AMOUNT to MaterialTheme.colorScheme.primaryContainer,
    SemanticField.COUNTERPARTY_NAME to MaterialTheme.colorScheme.tertiaryContainer,
    SemanticField.PROVIDER_TXN_ID to MaterialTheme.colorScheme.secondaryContainer,
    SemanticField.BALANCE_AFTER to MaterialTheme.colorScheme.surfaceContainerHighest,
)

private fun SemanticField.labelRes(): Int = when (this) {
    SemanticField.AMOUNT -> R.string.rule_field_amount
    SemanticField.COUNTERPARTY_NAME -> R.string.rule_field_who
    SemanticField.PROVIDER_TXN_ID -> R.string.rule_field_reference
    SemanticField.BALANCE_AFTER -> R.string.rule_field_balance
    else -> R.string.rule_field_amount
}

internal const val TAG_MESSAGE_BODY = "rule_builder_message"
internal const val TAG_SAVE_RULE = "rule_builder_save"

internal fun tagChipFor(field: SemanticField): String = "rule_builder_chip_${field.name}"

@Preview(name = "Rule builder", showBackground = true, heightDp = 900)
@Composable
private fun RuleBuilderPreview() {
    MezgebTheme {
        RuleBuilderScreen(
            state = RuleBuilderUiState(
                isLoading = false,
                sender = "AWASH",
                messages = listOf(
                    RawMessage(
                        id = "m1",
                        sender = "AWASH",
                        body = "Dear customer, ETB 450.00 has been credited to your " +
                            "account from ABEBE. Ref AW11223344.",
                        contentHash = "h",
                        receivedAt = 1_782_983_640_000L,
                        source = et.alpha.mezgeb.domain.model.CaptureSource.NOTIFICATION,
                        parseState = et.alpha.mezgeb.domain.model.ParseState.UNMATCHED,
                        matchedRuleId = null,
                    ),
                ),
                spans = mapOf(SemanticField.AMOUNT to IntRange(19, 24)),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
