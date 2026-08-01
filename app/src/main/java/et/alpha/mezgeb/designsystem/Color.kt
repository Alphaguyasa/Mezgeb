package et.alpha.mezgeb.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Mezgeb's palette is chosen, not generated.
 *
 * Two constraints drove it:
 *
 *  1. **Daylight legibility.** This app is used standing in a shop doorway with sun
 *     on the screen. Surfaces are near-white with very dark text, and the money
 *     colours are fully saturated rather than pastel.
 *
 *  2. **Money direction must survive colour blindness.** Money-in and money-out are
 *     drawn from the Okabe–Ito qualitative palette — blue against vermillion — which
 *     stays separable under deuteranopia, protanopia and tritanopia. Red/green would
 *     not. Colour is never the only signal regardless: every amount also carries a
 *     sign (+/−) and a direction icon, and in lists money-in and money-out occupy
 *     the same column so magnitude comparison never depends on hue.
 */

// --- Brand ---------------------------------------------------------------------
// Deep ledger green. Distinct from both money colours so app chrome never reads as
// a money signal.
internal val MezgebGreen10 = Color(0xFF002114)
internal val MezgebGreen20 = Color(0xFF003825)
internal val MezgebGreen30 = Color(0xFF005138)
internal val MezgebGreen40 = Color(0xFF0B6E4F)
internal val MezgebGreen80 = Color(0xFF62DBAB)
internal val MezgebGreen90 = Color(0xFF81F8C6)

// Warm sand secondary — receipts, paper, the physical ledger this replaces.
internal val SandGrey30 = Color(0xFF4B4A3F)
internal val SandGrey40 = Color(0xFF63614F)
internal val SandGrey80 = Color(0xFFCDC9B4)
internal val SandGrey90 = Color(0xFFE9E5CF)

// Muted indigo tertiary — used for informational accents, never for money.
internal val Indigo30 = Color(0xFF23405E)
internal val Indigo40 = Color(0xFF3D5A80)
internal val Indigo80 = Color(0xFFA7C8EE)
internal val Indigo90 = Color(0xFFCFE4FF)

// --- Neutrals ------------------------------------------------------------------
internal val Ink = Color(0xFF16130E)
internal val InkVariant = Color(0xFF4A463C)
internal val Paper = Color(0xFFFDFCF5)
internal val PaperVariant = Color(0xFFF0EEE3)
internal val OutlineLight = Color(0xFF7C7768)
internal val OutlineDark = Color(0xFF969080)

internal val NightSurface = Color(0xFF14130E)
internal val NightSurfaceVariant = Color(0xFF4A463C)
internal val NightOnSurface = Color(0xFFE8E2D6)
internal val NightOnSurfaceVariant = Color(0xFFCDC7B8)

// --- Errors --------------------------------------------------------------------
internal val ErrorRed40 = Color(0xFFB3261E)
internal val ErrorRed80 = Color(0xFFFFB4AB)
internal val ErrorRed90 = Color(0xFFFFDAD6)
internal val ErrorRed10 = Color(0xFF410E0B)

// --- Money direction (Okabe–Ito) ------------------------------------------------
/** Okabe–Ito blue. Money arriving. */
internal val MoneyInLight = Color(0xFF00598C)
internal val MoneyInDark = Color(0xFF7FCDF5)
internal val MoneyInContainerLight = Color(0xFFCDE7FA)
internal val MoneyInContainerDark = Color(0xFF00405F)

/** Okabe–Ito vermillion. Money leaving. Deliberately not the error red. */
internal val MoneyOutLight = Color(0xFF9E4400)
internal val MoneyOutDark = Color(0xFFFFB77C)
internal val MoneyOutContainerLight = Color(0xFFFFDCC4)
internal val MoneyOutContainerDark = Color(0xFF6E2E00)

// --- Channel accents ------------------------------------------------------------
// One hue per capture channel so a ChannelChip is recognisable at a glance without
// reading it. These are chip backgrounds; foregrounds are computed for contrast.
internal val ChannelTelebirrLight = Color(0xFFDCE6FF)
internal val ChannelTelebirrDark = Color(0xFF2A3F6B)
internal val ChannelCbeLight = Color(0xFFE2E9D4)
internal val ChannelCbeDark = Color(0xFF3B4630)
internal val ChannelBankLight = Color(0xFFE7DFF5)
internal val ChannelBankDark = Color(0xFF3E3554)
internal val ChannelCashLight = Color(0xFFF6E3C8)
internal val ChannelCashDark = Color(0xFF4E3D25)
internal val ChannelOtherLight = Color(0xFFE4E1D8)
internal val ChannelOtherDark = Color(0xFF3C3A33)
