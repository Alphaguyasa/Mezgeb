package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme

/**
 * The blank-slate state for a screen.
 *
 * Empty is not automatically a failure. Some of this app's empty states are good news
 * — an empty review queue means every message parsed — so callers supply their own
 * wording rather than getting a generic apology.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Filled.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val spacing = MezgebTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.xl, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = spacing.lg),
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = spacing.sm)
                    .widthIn(max = 320.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = spacing.xl)
                    .heightIn(min = spacing.touchTarget),
            ) {
                Text(actionLabel)
            }
        }
    }
}

// Rendered in Amharic on purpose: Ethiopic is the taller script and the one that
// wraps, so a preview in English would not show the layout that has to hold.
@Preview(name = "EmptyState (አማርኛ)", showBackground = true, heightDp = 420, locale = "am")
@Composable
private fun EmptyStatePreview() {
    MezgebTheme {
        Surface {
            EmptyState(
                title = stringResource(R.string.today_empty_title),
                body = stringResource(R.string.today_empty_body_nothing_yet),
                actionLabel = stringResource(R.string.onboarding_grant_action),
                onAction = {},
            )
        }
    }
}
