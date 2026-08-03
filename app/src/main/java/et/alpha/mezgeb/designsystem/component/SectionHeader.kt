package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.domain.model.Direction

/**
 * A titled divider between list sections — day groups in the ledger, sender groups in
 * the review queue. [trailing] carries the section's subtotal or count.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screenGutter, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        trailing?.invoke()
    }
}

@Preview(name = "SectionHeader (አማርኛ)", showBackground = true, locale = "am")
@Composable
private fun SectionHeaderPreview() {
    MezgebTheme {
        Surface {
            SectionHeader(title = stringResource(R.string.day_today)) {
                // Rendered through MoneyText rather than as a literal, so the preview
                // shows the grouping and sign the real subtotal will use.
                MoneyText(
                    amountMinor = 245_000L,
                    direction = Direction.IN,
                    style = MaterialTheme.typography.labelLarge,
                    showCurrency = true,
                    showIcon = false,
                )
            }
        }
    }
}
