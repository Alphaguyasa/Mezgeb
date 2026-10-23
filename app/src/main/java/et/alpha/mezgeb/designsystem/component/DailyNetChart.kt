package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import et.alpha.mezgeb.designsystem.MezgebTheme
import kotlin.math.abs
import kotlin.math.max

/** One bar. [netMinor] may be negative — a day the shop paid out more than it took. */
data class ChartBar(val label: String, val netMinor: Long)

/**
 * Daily net as bars, drawn with [Canvas] rather than a charting library.
 *
 * One chart does not justify a dependency, and the ones that would fit bring a
 * rendering stack, a theming system and a release cadence with them. This is sixty
 * lines and it does exactly what this screen needs.
 *
 * Three things it has to get right, all of which the obvious implementation gets
 * wrong:
 *
 * - **Negative bars.** A shop that paid out more than it took has a real answer, and
 *   it is not zero. The baseline sits where zero is, not at the bottom.
 * - **Empty days.** Days with no activity are drawn as a gap on the baseline rather
 *   than skipped, so a week with three quiet days looks like a week with three quiet
 *   days.
 * - **Long periods.** A month does not fit on a phone at a readable bar width, so the
 *   chart scrolls horizontally instead of squeezing.
 *
 * A canvas says nothing to TalkBack, so the caller passes [contentDescription], a
 * spoken summary of the same bars (see `spokenDailyNet`). It sits on the scrolling
 * box, so the whole chart is one stop for TalkBack, whatever part of it is on screen.
 */
@Composable
fun DailyNetChart(
    bars: List<ChartBar>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    barWidth: Dp = 20.dp,
    barSpacing: Dp = 6.dp,
) {
    if (bars.isEmpty()) return

    val positive = MezgebTheme.colors.moneyIn
    val negative = MezgebTheme.colors.moneyOut
    val baselineColor = MaterialTheme.colorScheme.outlineVariant

    val chartWidth = barWidth * bars.size + barSpacing * (bars.size + 1)

    Box(
        modifier = modifier
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .horizontalScroll(rememberScrollState())
            .padding(vertical = MezgebTheme.spacing.sm),
    ) {
        Canvas(
            Modifier
                .width(chartWidth)
                .height(height),
        ) {
            // Scaled by the largest magnitude in either direction, so a mostly-positive
            // period does not flatten its one bad day into invisibility.
            val peak = bars.maxOf { abs(it.netMinor) }.coerceAtLeast(1L)
            val barWidthPx = barWidth.toPx()
            val spacingPx = barSpacing.toPx()

            // Zero sits in the middle only when there is something on both sides;
            // otherwise it sits at the edge and the bars get the full height.
            val hasNegative = bars.any { it.netMinor < 0 }
            val hasPositive = bars.any { it.netMinor > 0 }
            val baselineY = when {
                hasNegative && hasPositive -> size.height / 2f
                hasNegative -> 0f
                else -> size.height
            }
            val available = when {
                hasNegative && hasPositive -> size.height / 2f
                else -> size.height
            }

            drawLine(
                color = baselineColor,
                start = Offset(0f, baselineY),
                end = Offset(size.width, baselineY),
                strokeWidth = 1.dp.toPx(),
            )

            bars.forEachIndexed { index, bar ->
                val left = spacingPx + index * (barWidthPx + spacingPx)
                val magnitude = abs(bar.netMinor).toFloat() / peak * available
                // A day with activity too small to see still gets a sliver, because
                // "almost nothing" and "nothing" are different days.
                val barHeight = if (bar.netMinor == 0L) 0f else max(magnitude, 1.dp.toPx())

                if (barHeight == 0f) return@forEachIndexed

                val top = if (bar.netMinor >= 0) baselineY - barHeight else baselineY
                drawRect(
                    color = if (bar.netMinor >= 0) positive else negative,
                    topLeft = Offset(left, top),
                    size = Size(barWidthPx, barHeight),
                )
            }
        }
    }
}

@Preview(name = "DailyNetChart", showBackground = true, widthDp = 360)
@Composable
private fun DailyNetChartPreview() {
    MezgebTheme {
        Surface {
            DailyNetChart(
                bars = listOf(
                    ChartBar("Mon", 45_000),
                    ChartBar("Tue", 12_000),
                    // A quiet day, present rather than skipped.
                    ChartBar("Wed", 0),
                    ChartBar("Thu", -30_000),
                    ChartBar("Fri", 88_000),
                    ChartBar("Sat", 5_000),
                    ChartBar("Sun", 0),
                ),
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
