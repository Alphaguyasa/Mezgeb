package et.alpha.mezgeb.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = MezgebGreen40,
    onPrimary = Color.White,
    primaryContainer = MezgebGreen90,
    onPrimaryContainer = MezgebGreen10,
    secondary = SandGrey40,
    onSecondary = Color.White,
    secondaryContainer = SandGrey90,
    onSecondaryContainer = Color(0xFF1F1E13),
    tertiary = Indigo40,
    onTertiary = Color.White,
    tertiaryContainer = Indigo90,
    onTertiaryContainer = Color(0xFF001C36),
    error = ErrorRed40,
    onError = Color.White,
    errorContainer = ErrorRed90,
    onErrorContainer = ErrorRed10,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperVariant,
    onSurfaceVariant = InkVariant,
    outline = OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary = MezgebGreen80,
    onPrimary = MezgebGreen20,
    primaryContainer = MezgebGreen30,
    onPrimaryContainer = MezgebGreen90,
    secondary = SandGrey80,
    onSecondary = Color(0xFF343328),
    secondaryContainer = SandGrey30,
    onSecondaryContainer = SandGrey90,
    tertiary = Indigo80,
    onTertiary = Color(0xFF06304F),
    tertiaryContainer = Indigo30,
    onTertiaryContainer = Indigo90,
    error = ErrorRed80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = ErrorRed90,
    background = NightSurface,
    onBackground = NightOnSurface,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    outline = OutlineDark,
)

/**
 * Colours Material 3 has no slot for. Money direction and capture channel are Mezgeb
 * concepts, so they live alongside the [ColorScheme] rather than being smuggled into
 * an unrelated Material role.
 */
@Immutable
data class MezgebColors(
    val moneyIn: Color,
    val onMoneyInContainer: Color,
    val moneyInContainer: Color,
    val moneyOut: Color,
    val onMoneyOutContainer: Color,
    val moneyOutContainer: Color,
    val channelTelebirr: Color,
    val channelCbeBirr: Color,
    val channelBank: Color,
    val channelCash: Color,
    val channelOther: Color,
    val onChannel: Color,
)

private val LightMezgebColors = MezgebColors(
    moneyIn = MoneyInLight,
    onMoneyInContainer = Color(0xFF001E30),
    moneyInContainer = MoneyInContainerLight,
    moneyOut = MoneyOutLight,
    onMoneyOutContainer = Color(0xFF331300),
    moneyOutContainer = MoneyOutContainerLight,
    channelTelebirr = ChannelTelebirrLight,
    channelCbeBirr = ChannelCbeLight,
    channelBank = ChannelBankLight,
    channelCash = ChannelCashLight,
    channelOther = ChannelOtherLight,
    onChannel = Ink,
)

private val DarkMezgebColors = MezgebColors(
    moneyIn = MoneyInDark,
    onMoneyInContainer = Color(0xFFCDE7FA),
    moneyInContainer = MoneyInContainerDark,
    moneyOut = MoneyOutDark,
    onMoneyOutContainer = Color(0xFFFFDCC4),
    moneyOutContainer = MoneyOutContainerDark,
    channelTelebirr = ChannelTelebirrDark,
    channelCbeBirr = ChannelCbeDark,
    channelBank = ChannelBankDark,
    channelCash = ChannelCashDark,
    channelOther = ChannelOtherDark,
    onChannel = NightOnSurface,
)

val LocalMezgebColors = staticCompositionLocalOf { LightMezgebColors }

/**
 * Note the absence of a dynamic-colour option. Wallpaper-derived palettes cannot
 * guarantee that money-in and money-out stay distinguishable, and that separation is
 * a correctness property here rather than a matter of taste.
 */
@Composable
fun MezgebTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val mezgebColors = if (darkTheme) DarkMezgebColors else LightMezgebColors

    CompositionLocalProvider(
        LocalMezgebColors provides mezgebColors,
        LocalMezgebSpacing provides MezgebSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MezgebTypography,
            content = content,
        )
    }
}

/** Shorthand accessors, mirroring `MaterialTheme.colorScheme`. */
object MezgebTheme {
    val colors: MezgebColors
        @Composable get() = LocalMezgebColors.current

    val spacing: MezgebSpacing
        @Composable get() = LocalMezgebSpacing.current
}
