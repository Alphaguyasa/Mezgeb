package et.alpha.mezgeb.ui.tour

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme

/**
 * One stop of the demo tour, drawn above the bottom bar so the screen it explains
 * stays visible behind it. Buttons sit in a flow row so they wrap, not squeeze, at a
 * large font size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoTourCard(
    step: DemoTourStep,
    isFirst: Boolean,
    isLast: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        tonalElevation = spacing.xxs,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.screenGutter, vertical = spacing.md)
                // A screen reader announces each new stop without the user hunting for it.
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(
                    R.string.tour_progress,
                    step.ordinal + 1,
                    DemoTourState.stepCount,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = stringResource(step.titleRes), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(step.bodyRes), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = spacing.touchTarget),
                ) {
                    Text(stringResource(R.string.tour_close))
                }
                if (!isFirst) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.heightIn(min = spacing.touchTarget),
                    ) {
                        Text(stringResource(R.string.tour_back))
                    }
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier.heightIn(min = spacing.touchTarget),
                ) {
                    Text(stringResource(if (isLast) R.string.tour_finish else R.string.tour_next))
                }
            }
        }
    }
}

@Preview(name = "DemoTourCard", showBackground = true, widthDp = 360)
@Composable
private fun DemoTourCardPreview() {
    MezgebTheme {
        DemoTourCard(DemoTourStep.TODAY, isFirst = true, isLast = false, onBack = {}, onNext = {}, onClose = {})
    }
}

@Preview(name = "DemoTourCard (አማርኛ)", showBackground = true, widthDp = 360, locale = "am")
@Composable
private fun DemoTourCardAmharicPreview() {
    MezgebTheme {
        DemoTourCard(DemoTourStep.RECONCILE, isFirst = false, isLast = false, onBack = {}, onNext = {}, onClose = {})
    }
}
