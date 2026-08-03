package et.alpha.mezgeb.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import et.alpha.mezgeb.R
import et.alpha.mezgeb.designsystem.MezgebTheme
import et.alpha.mezgeb.designsystem.component.ChannelChip
import et.alpha.mezgeb.designsystem.component.MoneyText
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.RelativeDay
import java.time.ZoneId
import java.util.Locale

/**
 * A transaction shaped for a list row.
 *
 * The category *name* rather than its id, and one counterparty label rather than a
 * name and a phone: resolving those in the ViewModel keeps the row composable free
 * of lookups it would have to do once per visible item.
 */
data class TransactionUiModel(
    val id: String,
    val direction: Direction,
    val amountMinor: Long,
    val channel: Channel,
    /** Name if the message carried one, otherwise the phone, otherwise nothing. */
    val counterparty: String?,
    val occurredAt: Long,
    val categoryName: String?,
    val isEdited: Boolean,
    val isReconciled: Boolean,
)

fun Transaction.toUiModel(categoryName: String?): TransactionUiModel = TransactionUiModel(
    id = id,
    direction = direction,
    amountMinor = amountMinor,
    channel = channel,
    counterparty = counterpartyName ?: counterpartyPhone,
    occurredAt = occurredAt,
    categoryName = categoryName,
    isEdited = isEdited,
    isReconciled = isReconciled,
)

/**
 * One line of the ledger: who, through what, how much, when.
 *
 * The amount is the only thing on the right, at the end of the scan path, because it
 * is what the row is read for.
 */
@Composable
fun TransactionRow(
    transaction: TransactionUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = spacing.touchTarget)
            .padding(horizontal = spacing.screenGutter, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = transaction.counterparty
                    ?: stringResource(R.string.transaction_no_counterparty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ChannelChip(transaction.channel)
                Text(
                    text = rememberTimeLabel(transaction.occurredAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (transaction.categoryName != null) {
                    Text(
                        text = transaction.categoryName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (transaction.isEdited) {
                    // The user changed a parsed value. Saying so is the honest thing:
                    // this number is no longer only what the message said.
                    Text(
                        text = stringResource(R.string.transaction_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        MoneyText(
            amountMinor = transaction.amountMinor,
            direction = transaction.direction,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** A day separator with that day's in and out subtotals. */
@Composable
fun DayHeader(
    dayEpochMillis: Long,
    inMinor: Long,
    outMinor: Long,
    modifier: Modifier = Modifier,
) {
    val spacing = MezgebTheme.spacing
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = spacing.screenGutter,
                vertical = spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = rememberDayLabel(dayEpochMillis),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (inMinor > 0) {
                MoneyText(
                    amountMinor = inMinor,
                    direction = Direction.IN,
                    style = MaterialTheme.typography.labelLarge,
                    showIcon = false,
                )
            }
            if (outMinor > 0) {
                MoneyText(
                    amountMinor = outMinor,
                    direction = Direction.OUT,
                    style = MaterialTheme.typography.labelLarge,
                    showIcon = false,
                )
            }
        }
    }
}

/** "Today", "Yesterday", or a date. Localised through resources, not through code. */
@Composable
fun rememberDayLabel(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = rememberZone()
    val locale = currentLocale()
    val relative = remember(epochMillis, nowMillis, zone) {
        MezgebDates.relativeDay(epochMillis, nowMillis, zone)
    }
    return when (relative) {
        RelativeDay.TODAY -> stringResource(R.string.day_today)
        RelativeDay.YESTERDAY -> stringResource(R.string.day_yesterday)
        RelativeDay.EARLIER -> remember(epochMillis, zone, locale) {
            MezgebDates.formatDay(epochMillis, zone, locale)
        }
    }
}

/**
 * These two earn their `remember` prefix rather than only carrying it.
 *
 * Both are called once per visible row. Formatting an instant is not free — it walks a
 * zone rules table and builds a string — and doing it on every recomposition of a list
 * that recomposes on every scroll frame is the difference between a smooth ledger and a
 * stuttering one on the cheap phones this app is for.
 */
@Composable
fun rememberTimeLabel(epochMillis: Long): String {
    val zone = rememberZone()
    val locale = currentLocale()
    return remember(epochMillis, zone, locale) {
        MezgebDates.formatTime(epochMillis, zone, locale)
    }
}

/** `ZoneId.systemDefault()` reads a system property and parses it on every call. */
@Composable
private fun rememberZone(): ZoneId = remember { ZoneId.systemDefault() }

/**
 * No `Locale.getDefault()` fallback: reading it here would be a read Compose cannot
 * observe, so a language change would leave already-composed rows formatted in the old
 * one. `LocalConfiguration` is observable and its locale list is never empty.
 */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Preview(name = "TransactionRow", showBackground = true)
@Composable
private fun TransactionRowPreview() {
    MezgebTheme {
        Surface {
            Column {
                DayHeader(dayEpochMillis = 1_782_950_400_000L, inMinor = 145_000, outMinor = 22_500)
                TransactionRow(
                    transaction = TransactionUiModel(
                        id = "1",
                        direction = Direction.IN,
                        amountMinor = 45_000,
                        channel = Channel.TELEBIRR,
                        counterparty = "ABEBE",
                        occurredAt = 1_782_983_640_000L,
                        categoryName = "Sales",
                        isEdited = false,
                        isReconciled = false,
                    ),
                    onClick = {},
                )
                TransactionRow(
                    transaction = TransactionUiModel(
                        id = "2",
                        direction = Direction.OUT,
                        amountMinor = 22_500,
                        channel = Channel.CASH,
                        counterparty = null,
                        occurredAt = 1_782_990_000_000L,
                        categoryName = null,
                        isEdited = true,
                        isReconciled = false,
                    ),
                    onClick = {},
                )
            }
        }
    }
}
