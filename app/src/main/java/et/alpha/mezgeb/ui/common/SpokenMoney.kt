package et.alpha.mezgeb.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
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

/**
 * The daily-net chart as one spoken summary: how many days, how many ahead, behind
 * and even, then the best and the worst day as [spokenNet] sentences.
 *
 * The chart is drawn on a canvas, which has nothing for TalkBack to read, so without
 * this a TalkBack user hears the "Each day" heading and then silence. The best and the
 * worst day are the two bars a sighted owner looks for first. A quiet day counts as
 * even, the same as the chart draws it on the baseline. Best or worst is left out
 * when no day was on that side, rather than naming an even day as the best one.
 */
@Composable
fun spokenDailyNet(netsMinor: List<Long>): String {
    val ahead = netsMinor.count { it > 0 }
    val behind = netsMinor.count { it < 0 }
    val sentences = mutableListOf(
        pluralStringResource(
            R.plurals.report_daily_net_summary_content_description,
            netsMinor.size,
            netsMinor.size,
            ahead,
            behind,
            netsMinor.size - ahead - behind,
        ),
    )
    netsMinor.maxOrNull()?.takeIf { it > 0 }?.let { best ->
        sentences += stringResource(
            R.string.report_daily_net_best_content_description,
            spokenNet(best),
        )
    }
    netsMinor.minOrNull()?.takeIf { it < 0 }?.let { worst ->
        sentences += stringResource(
            R.string.report_daily_net_worst_content_description,
            spokenNet(worst),
        )
    }
    return sentences.joinToString(" ")
}
