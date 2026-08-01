package et.alpha.mezgeb.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import et.alpha.mezgeb.R

/**
 * Noto Sans Ethiopic, bundled rather than relied upon from the system.
 *
 * Android's font fallback chain does include Ethiopic, so in principle Amharic would
 * render without this. In practice several of the OEM ROMs common in this market ship
 * trimmed font sets, and a ledger that renders as tofu boxes is worse than useless.
 * Bundling costs ~1.1 MB and removes the variable entirely. The family also covers
 * Latin and Arabic numerals, so one family serves the whole app.
 *
 * This is a variable font; weights are instantiated through the `wght` axis, which
 * requires API 26 — our minSdk.
 */
@OptIn(ExperimentalTextApi::class)
private fun ethiopic(weight: Int) = Font(
    resId = R.font.noto_sans_ethiopic,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val MezgebFontFamily = FontFamily(
    ethiopic(400),
    ethiopic(500),
    ethiopic(600),
    ethiopic(700),
)

/**
 * Type scale, nudged up from the Material defaults.
 *
 * Body sizes are one step larger than stock because the primary reader is often
 * holding the phone at arm's length in bright light, and Ethiopic syllables carry
 * more detail per glyph than Latin letters do — they lose legibility faster as size
 * drops. Display styles are reserved for money totals.
 */
val MezgebTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W700,
        fontSize = 52.sp,
        lineHeight = 60.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W700,
        fontSize = 40.sp,
        lineHeight = 48.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 30.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 21.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W500,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W600,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W500,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = MezgebFontFamily,
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)
