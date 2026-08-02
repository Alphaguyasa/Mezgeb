package et.alpha.mezgeb.domain.credit

import android.content.Intent
import android.net.Uri
import et.alpha.mezgeb.util.MoneyFormatter

/**
 * Builds a payment reminder and hands it to the messaging app. It never sends it.
 *
 * This is a hard product line, not an implementation detail. The app has no
 * `SEND_SMS` permission and will not acquire one: an app that can send messages from
 * a shop owner's number to her customers, on its own judgement, is a different and
 * much worse product than this one. `ACTION_SENDTO` opens the user's own messaging
 * app with the text prefilled, and a person presses send.
 */
object ReminderMessage {

    /**
     * `{name}` and `{amount}` are substituted; anything else in the template is left
     * exactly as the shop wrote it.
     */
    fun render(template: String, customerName: String, balanceMinor: Long): String = template
        .replace(PLACEHOLDER_NAME, customerName)
        .replace(
            PLACEHOLDER_AMOUNT,
            MoneyFormatter.format(balanceMinor, currency = MoneyFormatter.DEFAULT_CURRENCY),
        )

    /**
     * An intent that opens the messaging app with the message ready to send.
     *
     * `sms_body` alongside [Intent.EXTRA_TEXT] on purpose: the documented extra is
     * `EXTRA_TEXT`, but every Android messaging app in practice reads `sms_body`, and
     * a reminder that opens with an empty compose box is a reminder the shop owner has
     * to type out again.
     */
    fun intent(phone: String, message: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.filter { it.isDigit() || it == '+' }}"))
            .putExtra("sms_body", message)
            .putExtra(Intent.EXTRA_TEXT, message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val PLACEHOLDER_NAME = "{name}"
    const val PLACEHOLDER_AMOUNT = "{amount}"
}
