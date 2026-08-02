package et.alpha.mezgeb.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.ReprocessStatus
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.BackButton
import et.alpha.mezgeb.designsystem.component.EmptyState
import et.alpha.mezgeb.designsystem.component.MezgebScaffold
import et.alpha.mezgeb.domain.model.UnmatchedGroup
import et.alpha.mezgeb.ui.common.rememberDayLabel

@Composable
fun ReviewRoute(
    onBack: () -> Unit,
    onTeachRule: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReviewScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onTeachRule = onTeachRule,
        modifier = modifier,
    )
}

/**
 * The queue of messages no rule could read.
 *
 * Note the empty state: an empty queue means every captured message parsed cleanly,
 * so it is drawn as a success rather than as an apology. A user who learns to read
 * "nothing here" as failure stops opening the screen.
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
    onBack: () -> Unit,
    onTeachRule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    MezgebScaffold(
        title = stringResource(R.string.destination_review),
        modifier = modifier,
        navigationIcon = { BackButton(onBack) },
        actions = {
            if (!state.isEmpty) {
                TextButton(onClick = { onEvent(ReviewEvent.Reprocess) }) {
                    Text(stringResource(R.string.review_try_again))
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            ReprocessBanner(state.reprocess)

            when {
                state.isEmpty && !state.isLoading -> EmptyState(
                    title = stringResource(R.string.review_empty_title),
                    body = stringResource(R.string.review_empty_body),
                    icon = Icons.Filled.CheckCircle,
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item("intro") {
                        Text(
                            text = stringResource(R.string.review_intro, state.totalWaiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MezgebTheme.spacing.screenGutter),
                        )
                    }
                    items(state.groups, key = { it.sender }) { group ->
                        SenderGroupRow(group = group, onClick = { onTeachRule(group.sender) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReprocessBanner(status: ReprocessStatus) {
    val spacing = MezgebTheme.spacing
    when (status) {
        is ReprocessStatus.Running -> Column(
            Modifier.padding(horizontal = spacing.screenGutter, vertical = spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.review_reprocessing, status.scanned, status.total),
                style = MaterialTheme.typography.bodySmall,
            )
            if (status.total > 0) {
                LinearProgressIndicator(
                    progress = { status.scanned.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        is ReprocessStatus.Finished -> if (status.scanned > 0) {
            Text(
                text = stringResource(R.string.review_reprocessed, status.recorded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = spacing.screenGutter,
                    vertical = spacing.sm,
                ),
            )
        }

        ReprocessStatus.Failed, ReprocessStatus.Idle -> Unit
    }
}

/**
 * One sender, one template, one rule to teach. Grouping is what turns "forty
 * problems" into "three".
 */
@Composable
private fun SenderGroupRow(group: UnmatchedGroup, onClick: () -> Unit) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = spacing.touchTarget)
            .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = group.sender,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.review_group_summary,
                    group.messageCount,
                    rememberDayLabel(group.latestReceivedAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.review_teach_rule),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview(name = "Review queue", showBackground = true, heightDp = 640)
@Composable
private fun ReviewScreenPreview() {
    MezgebTheme {
        ReviewScreen(
            state = ReviewUiState(
                isLoading = false,
                groups = listOf(
                    UnmatchedGroup("AWASH", 14, 1_782_983_640_000L),
                    UnmatchedGroup("DASHEN", 2, 1_782_900_000_000L),
                ),
            ),
            onEvent = {},
            onBack = {},
            onTeachRule = {},
        )
    }
}

@Preview(name = "Review queue (አማርኛ, empty)", showBackground = true, heightDp = 640, locale = "am")
@Composable
private fun ReviewScreenEmptyPreview() {
    MezgebTheme {
        ReviewScreen(
            state = ReviewUiState(isLoading = false),
            onEvent = {},
            onBack = {},
            onTeachRule = {},
        )
    }
}
