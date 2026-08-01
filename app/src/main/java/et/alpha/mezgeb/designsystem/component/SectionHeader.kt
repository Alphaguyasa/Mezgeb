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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.designsystem.MezgebTheme

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

@Preview(name = "SectionHeader", showBackground = true)
@Composable
private fun SectionHeaderPreview() {
    MezgebTheme {
        Surface {
            SectionHeader(title = "ዛሬ") {
                Text("+2,450.00 ETB", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
