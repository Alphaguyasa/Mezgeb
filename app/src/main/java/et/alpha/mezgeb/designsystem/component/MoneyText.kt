package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.util.MoneyFormatter

/**
 * An amount, rendered so that its direction is readable three ways at once: colour,
 * an explicit +/− sign, and a direction arrow. Colour alone is never load-bearing —
 * see the note in `Color.kt`.
 *
 * [amountMinor] is always supplied as a positive magnitude; [direction] carries the
 * sign, matching how `TransactionEntity` stores it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoneyText(
    amountMinor: Long,
    direction: Direction,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    showCurrency: Boolean = false,
    showIcon: Boolean = true,
) {
    val colors = MezgebTheme.colors
    val tint = when (direction) {
        Direction.IN -> colors.moneyIn
        Direction.OUT -> colors.moneyOut
    }
    val signedMinor = when (direction) {
        Direction.IN -> amountMinor
        Direction.OUT -> -amountMinor
    }
    // The currency is a Text of its own, not part of the amount's string, so that it
    // can move to the next line as a whole when the two do not fit (see below).
    val formatted = MoneyFormatter.format(
        amountMinor = signedMinor,
        currency = null,
        signed = true,
    )

    val spokenDirection = stringResource(
        when (direction) {
            Direction.IN -> R.string.direction_in
            Direction.OUT -> R.string.direction_out
        },
    )
    // Read the amount as words rather than letting TalkBack spell out "+1,234.56".
    val spokenAmount = stringResource(
        R.string.money_content_description,
        spokenDirection,
        MoneyFormatter.formatUnsigned(amountMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = spokenAmount },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Icon(
                imageVector = when (direction) {
                    // Money arriving points down into the ledger; money leaving points up.
                    Direction.IN -> Icons.Filled.KeyboardArrowDown
                    Direction.OUT -> Icons.Filled.KeyboardArrowUp
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size((style.fontSize.value * 1.15f).dp),
            )
            Spacer(Modifier.width(MezgebTheme.spacing.xxs))
        }
        if (showCurrency) {
            // A flow rather than one string. Today's headline at the largest system
            // font measured 278dp of the 296dp it has with "+12,450.00 ETB", so a
            // six-figure total would wrap inside the number. Here the amount keeps
            // the whole width, and only "ETB" moves under it (ROADMAP R26). The gap
            // is a space's width at this size, so a line that fits reads as before.
            val gap = with(LocalDensity.current) { (style.fontSize * SPACE_EM).toDp() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Text(text = formatted, style = style, color = tint)
                Text(text = MoneyFormatter.DEFAULT_CURRENCY, style = style, color = tint)
            }
        } else {
            Text(
                text = formatted,
                style = style,
                color = tint,
            )
        }
    }
}

/** About the width of a space in the app's font, as a fraction of the font size. */
private const val SPACE_EM = 0.25f

@Preview(name = "MoneyText light", showBackground = true)
@Preview(name = "MoneyText dark", showBackground = true, uiMode = 0x20)
@Composable
private fun MoneyTextPreview() {
    MezgebTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MoneyText(amountMinor = 123_456, direction = Direction.IN, showCurrency = true)
                MoneyText(amountMinor = 4_500_075, direction = Direction.OUT, showCurrency = true)
                MoneyText(
                    amountMinor = 50_000,
                    direction = Direction.IN,
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }
    }
}
