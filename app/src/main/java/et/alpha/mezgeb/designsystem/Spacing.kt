package et.alpha.mezgeb.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens. Use these rather than raw dp values so that density decisions are
 * made once. The scale is 4dp-based; [touchTarget] is the accessibility floor and
 * every interactive element must meet it.
 */
@Immutable
data class MezgebSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    /** Standard horizontal inset for screen content. */
    val screenGutter: Dp = 16.dp,
    /** Minimum size of anything tappable. */
    val touchTarget: Dp = 48.dp,
)

val LocalMezgebSpacing = staticCompositionLocalOf { MezgebSpacing() }
