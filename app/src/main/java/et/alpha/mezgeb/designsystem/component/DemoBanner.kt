package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme

/**
 * Whether the app is showing demo data.
 *
 * A CompositionLocal rather than a parameter on every screen, because the banner is a
 * property of the whole app rather than of any one screen, and threading a boolean
 * through fourteen screens to reach one strip of colour would be worse than this.
 * Defaults to false, so previews and tests see the real frame unless they say otherwise.
 */
val LocalDemoMode = staticCompositionLocalOf { false }

/**
 * A strip under the app bar, present on every screen while demo mode is on.
 *
 * Deliberately not dismissible. Someone looking at a demo needs to be able to tell at
 * any moment that the numbers are invented, and a banner that can be closed is one that
 * will be closed and then forgotten about — at which point the app is quietly showing a
 * fabricated ledger with nothing to say so.
 */
@Composable
fun DemoBanner(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.demo_banner_content_description)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MezgebTheme.spacing.screenGutter, vertical = 6.dp)
                // One announcement, not an icon and a label read separately.
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(MezgebTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.demo_banner),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Preview(name = "DemoBanner", showBackground = true, widthDp = 360)
@Composable
private fun DemoBannerPreview() {
    MezgebTheme {
        DemoBanner()
    }
}

@Preview(name = "DemoBanner (አማርኛ)", showBackground = true, widthDp = 360, locale = "am")
@Composable
private fun DemoBannerAmharicPreview() {
    MezgebTheme {
        DemoBanner()
    }
}
