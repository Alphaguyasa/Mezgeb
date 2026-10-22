package et.alpha.mezgeb.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import et.alpha.mezgeb.R
import et.alpha.mezgeb.util.MoneyFormatter

/**
 * A net (money in minus money out) as a sentence: which side is ahead, and by how
 * much. Shared by Today and Reports, the two screens that show one.
 *
 * TalkBack reads "−820.00 ETB" as a symbol in front of digits, and some speech
 * engines drop the sign entirely, which turns a day that lost money into one that
 * made it. A sentence cannot be misread that way.
 */
@Composable
fun spokenNet(netMinor: Long): String = when {
    netMinor > 0 -> stringResource(
        R.string.today_net_ahead_content_description,
        MoneyFormatter.formatUnsigned(netMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )
    netMinor < 0 -> stringResource(
        R.string.today_net_behind_content_description,
        MoneyFormatter.formatUnsigned(netMinor),
        MoneyFormatter.DEFAULT_CURRENCY,
    )
    else -> stringResource(R.string.today_net_even_content_description)
}
