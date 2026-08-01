package et.alpha.mezgeb.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.domain.model.Channel

/** Display label for a [Channel]. Kept here so every screen names channels identically. */
@Composable
fun Channel.label(): String = stringResource(
    when (this) {
        Channel.TELEBIRR -> R.string.channel_telebirr
        Channel.CBE_BIRR -> R.string.channel_cbe_birr
        Channel.BANK -> R.string.channel_bank
        Channel.CASH -> R.string.channel_cash
        Channel.OTHER -> R.string.channel_other
    },
)

@Composable
private fun Channel.containerColor(): Color = with(MezgebTheme.colors) {
    when (this@containerColor) {
        Channel.TELEBIRR -> channelTelebirr
        Channel.CBE_BIRR -> channelCbeBirr
        Channel.BANK -> channelBank
        Channel.CASH -> channelCash
        Channel.OTHER -> channelOther
    }
}

/**
 * A small, non-interactive tag identifying where a transaction came from. One hue per
 * channel so a ledger row is scannable without reading the label.
 */
@Composable
fun ChannelChip(
    channel: Channel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = channel.containerColor(),
        contentColor = MezgebTheme.colors.onChannel,
    ) {
        Text(
            text = channel.label(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Preview(name = "ChannelChip", showBackground = true)
@Composable
private fun ChannelChipPreview() {
    MezgebTheme {
        Surface {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(PaddingValues(12.dp)),
            ) {
                Channel.entries.forEach { ChannelChip(it) }
            }
        }
    }
}
