package et.alpha.mezgeb.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme

/**
 * Standard up affordance for pushed screens. Auto-mirrors under RTL layouts.
 *
 * Sized explicitly to [MezgebTheme.spacing].touchTarget. Material3's `IconButton`
 * defaults to a 40dp state layer, and on a device that is what the accessibility node
 * reports — measured at 105x105 px on a 420dpi phone, which is 40dp against the 48dp
 * floor this project sets for itself. Every other control in the app clears that floor;
 * this one did not, and it is the most-used control there is, sitting on the corner of
 * nearly every pushed screen.
 */
@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(MezgebTheme.spacing.touchTarget),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_navigate_back),
        )
    }
}
