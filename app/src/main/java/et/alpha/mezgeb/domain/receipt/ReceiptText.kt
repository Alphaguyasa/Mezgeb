package et.alpha.mezgeb.domain.receipt

import android.content.Intent
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.util.MoneyFormatter

/**
 * One ledger entry as a small plain-text receipt, for the share sheet.
 *
 * The cheap Bluetooth thermal printers shops already own are driven by printer apps
 * that accept shared plain text, so plain text through the share sheet is enough to
 * print, and the same text goes just as well to Telegram or SMS. No printer SDK, no
 * Bluetooth permission and no network: the receiving app does the printing.
 *
 * Every line is at most [WIDTH] characters, the width of the common 58 mm paper roll,
 * so nothing wraps mid-word on paper. Wider printers simply leave a margin.
 *
 * What is left out matters as much as what goes in, because a receipt is handed to
 * the customer:
 * - The wallet balance after the payment is the shop's own money, not the customer's
 *   business.
 * - The counterparty's phone number is left out: the name and the provider reference
 *   identify the payment, and a printed slip with a number on it travels.
 * - The note is what the shop wrote for itself, as on the shared statement.
 */
object ReceiptText {

    /** Characters per line on a 58 mm roll, the narrowest common size. */
    const val WIDTH = 32

    /**
     * Every word, already translated, for the same reasons as the statement's labels:
     * resources belong to the caller, and the tests can check the layout with plain
     * strings.
     */
    data class Labels(
        val title: String,
        val received: String,
        val paid: String,
        val via: String,
        val from: String,
        val to: String,
        val reference: String,
        val thanks: String,
    )

    /**
     * @param shopName the shop's name from Settings, or null/blank when none was set,
     *   in which case the receipt simply starts with its title.
     * @param channelLabel the channel as the app names it ("telebirr", "Cash").
     * @param formattedWhen the entry's date and time, already in the shop's zone and
     *   language.
     */
    fun build(
        transaction: Transaction,
        shopName: String?,
        channelLabel: String,
        formattedWhen: String,
        labels: Labels,
    ): String {
        val lines = mutableListOf<String>()
        shopName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            wrap(name).forEach { lines += center(it) }
            lines += "=".repeat(WIDTH)
        }
        lines += center(labels.title)
        wrap(formattedWhen).forEach { lines += center(it) }
        lines += "-".repeat(WIDTH)

        val kind = when (transaction.direction) {
            Direction.IN -> labels.received
            Direction.OUT -> labels.paid
        }
        lines += spread(kind, MoneyFormatter.format(transaction.amountMinor))

        lines += wrap("${labels.via}: $channelLabel")
        transaction.counterpartyName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            val party = when (transaction.direction) {
                Direction.IN -> labels.from
                Direction.OUT -> labels.to
            }
            lines += wrap("$party: $name")
        }
        transaction.providerTxnId?.trim()?.takeIf { it.isNotEmpty() }?.let { ref ->
            lines += wrap("${labels.reference}: $ref")
        }

        lines += "-".repeat(WIDTH)
        wrap(labels.thanks).forEach { lines += center(it) }
        return lines.joinToString("\n")
    }

    /** The share sheet with the receipt, as a chooser so the app is picked each time. */
    fun intent(text: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, chooserTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Label on the left, amount on the right of one line. When both do not fit, the
     * amount gets a line of its own rather than being cut: a receipt with half a
     * number on it is worse than a longer receipt.
     */
    internal fun spread(left: String, right: String): String {
        val gap = WIDTH - left.length - right.length
        return if (gap >= 1) {
            left + " ".repeat(gap) + right
        } else {
            (wrap(left) + right.padStart(WIDTH)).joinToString("\n")
        }
    }

    internal fun center(text: String): String {
        val pad = (WIDTH - text.length) / 2
        return if (pad > 0) " ".repeat(pad) + text else text
    }

    /**
     * Word wrap to [WIDTH]. A word longer than a whole line (a long reference) is cut
     * at the width, because a printer would cut it there anyway, only less tidily.
     */
    internal fun wrap(text: String): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > WIDTH) {
                if (line.isNotEmpty()) {
                    out += line.toString()
                    line = StringBuilder()
                }
                out += rest.take(WIDTH)
                rest = rest.drop(WIDTH)
            }
            if (rest.isEmpty()) continue
            if (line.isEmpty()) {
                line.append(rest)
            } else if (line.length + 1 + rest.length <= WIDTH) {
                line.append(' ').append(rest)
            } else {
                out += line.toString()
                line = StringBuilder(rest)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }
}
