package et.alpha.mezgeb.domain.credit

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderMessageTest {

    @Test
    fun `placeholders are filled and everything else is left alone`() {
        val rendered = ReminderMessage.render(
            template = "Hello {name}, about {amount} outstanding. Shop closes at 8.",
            customerName = "Abebe",
            balanceMinor = 45_000,
        )

        assertEquals("Hello Abebe, about 450.00 ETB outstanding. Shop closes at 8.", rendered)
    }

    @Test
    fun `a template without placeholders is sent as written`() {
        val rendered = ReminderMessage.render(
            template = "Please come by the shop.",
            customerName = "Abebe",
            balanceMinor = 45_000,
        )

        assertEquals("Please come by the shop.", rendered)
    }

    @Test
    fun `an amharic template renders the same way`() {
        val rendered = ReminderMessage.render(
            template = "ሰላም {name}፣ ስለቀረው {amount} ለማስታወስ ነው።",
            customerName = "አበበ",
            balanceMinor = 120_050,
        )

        assertEquals("ሰላም አበበ፣ ስለቀረው 1,200.50 ETB ለማስታወስ ነው።", rendered)
    }

    @Test
    fun `the intent opens the messaging app and does not send`() {
        val intent = ReminderMessage.intent("+251911234567", "Hello Abebe")

        // ACTION_SENDTO opens a compose screen. ACTION_SEND, or anything touching
        // SEND_SMS, would put the shop owner's name on a message she never read.
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto:+251911234567", intent.data.toString())
        assertEquals("Hello Abebe", intent.getStringExtra("sms_body"))
        assertEquals("Hello Abebe", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `phone punctuation is stripped from the uri but the plus is kept`() {
        val intent = ReminderMessage.intent("+251 911-234-567", "hi")

        assertEquals("smsto:+251911234567", intent.data.toString())
    }

    @Test
    fun `the intent is never a send action`() {
        val intent = ReminderMessage.intent("0911234567", "hi")

        assertTrue(intent.action != Intent.ACTION_SEND)
        assertTrue(intent.action != "android.intent.action.SENDTO_SMS")
    }
}
