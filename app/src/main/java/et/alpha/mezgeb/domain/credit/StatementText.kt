package et.alpha.mezgeb.domain.credit

import android.content.Intent
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.CreditStatementLine
import et.alpha.mezgeb.util.MoneyFormatter

/**
 * A customer's statement as plain text, for the share sheet (ROADMAP R44).
 *
 * When a customer disputes a balance, the reminder's single figure does not settle
 * anything. The dated lines and the balance after each one do, so this writes out
 * exactly what the statement screen shows, oldest first, in the order the database
 * gave it.
 *
 * Like [ReminderMessage], this never sends anything. [intent] opens the system share
 * sheet and a person picks where the text goes. No permission and no network is
 * involved; the receiving app does the sending, if any.
 *
 * Notes are left out on purpose. A note is what the shop wrote for itself ("pays on
 * the 15th", "brother of Kebede"), and the shop owner sharing a statement is not
 * thinking about it going along. The kind, the day, the amount and the balance are
 * what a dispute is about.
 */
object StatementText {

    /**
     * Every word in the text, already translated.
     *
     * Passed in rather than read here, for the same reason as the reminder template:
     * the words are resources for the current language, and this object holds no
     * Context. It also lets the tests check the layout with plain strings.
     *
     * The templates use `{name}`-style placeholders, like the reminder template, so
     * they are not format strings and a translator can move them freely.
     */
    data class Labels(
        /** `{name}` is the customer's name. */
        val title: String,
        val kindGiven: String,
        val kindRepayment: String,
        val kindAdjustment: String,
        /** `{day}`, `{kind}`, `{amount}` (signed) and `{balance}` (after this line). */
        val line: String,
        /** `{amount}` is what the customer owes the shop. */
        val balanceOwed: String,
        /** `{amount}` is what the shop owes the customer. */
        val balanceShopOwes: String,
        val balanceSettled: String,
        val empty: String,
    )

    /**
     * @param lines the statement, oldest first, as the repository returns it.
     * @param balanceMinor the customer's balance now. Positive means they owe the shop.
     * @param formatDay turns an entry's instant into a date. A full date with the year,
     *   because a statement can reach back far enough for "1 Jul" to be ambiguous.
     */
    fun build(
        customerName: String,
        lines: List<CreditStatementLine>,
        balanceMinor: Long,
        labels: Labels,
        formatDay: (Long) -> String,
    ): String = buildString {
        appendLine(labels.title.fill(NAME to customerName))
        appendLine()

        if (lines.isEmpty()) {
            appendLine(labels.empty)
        } else {
            lines.forEach { line ->
                appendLine(
                    labels.line.fill(
                        DAY to formatDay(line.entry.occurredAt),
                        KIND to labels.kindOf(line.entry.kind),
                        AMOUNT to signedAmount(line),
                        // Signed in the text, unlike on screen for TalkBack: a written
                        // minus is the plainest way to show the shop holding money.
                        BALANCE to MoneyFormatter.format(line.runningBalanceMinor),
                    ),
                )
            }
        }

        appendLine()
        append(
            when {
                balanceMinor > 0 -> labels.balanceOwed.fill(
                    AMOUNT to MoneyFormatter.format(balanceMinor),
                )
                balanceMinor < 0 -> labels.balanceShopOwes.fill(
                    AMOUNT to MoneyFormatter.format(-balanceMinor),
                )
                else -> labels.balanceSettled
            },
        )
    }

    /**
     * The share sheet, with the text ready. A chooser, so the shop owner picks the app
     * every time instead of the text going wherever it went last.
     */
    fun intent(text: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, chooserTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The same signs the screen shows: a repayment lowers the balance, the rest raise it. */
    private fun signedAmount(line: CreditStatementLine): String {
        val unsigned = MoneyFormatter.formatUnsigned(line.entry.amountMinor)
        val sign = if (line.entry.kind == CreditKind.REPAYMENT) "−" else "+"
        return "$sign$unsigned ${MoneyFormatter.DEFAULT_CURRENCY}"
    }

    private fun Labels.kindOf(kind: CreditKind): String = when (kind) {
        CreditKind.CREDIT_GIVEN -> kindGiven
        CreditKind.REPAYMENT -> kindRepayment
        CreditKind.ADJUSTMENT -> kindAdjustment
    }

    /**
     * Fills every placeholder in one pass. A customer named "{amount}" is printed as
     * typed instead of being filled in by a later value, and an unknown placeholder in
     * a translation is left visible rather than dropped.
     */
    private fun String.fill(vararg values: Pair<String, String>): String {
        val byKey = values.toMap()
        return PLACEHOLDER.replace(this) { match -> byKey[match.value] ?: match.value }
    }

    private const val NAME = "{name}"
    private const val DAY = "{day}"
    private const val KIND = "{kind}"
    private const val AMOUNT = "{amount}"
    private const val BALANCE = "{balance}"
    private val PLACEHOLDER = Regex("\\{[a-z]+\\}")
}
