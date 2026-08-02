package et.alpha.mezgeb.parser.normalize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountSnifferTest {

    @Test
    fun `an amount after the currency is found`() {
        assertEquals(45_000L, AmountSniffer.sniff("You have received ETB 450.00 from ABEBE"))
    }

    @Test
    fun `an amount before the currency is found`() {
        assertEquals(45_000L, AmountSniffer.sniff("You have received 450.00 birr from ABEBE"))
    }

    @Test
    fun `no space between currency and amount is still an amount`() {
        assertEquals(20_000L, AmountSniffer.sniff("You have successfully transferred ETB200"))
    }

    @Test
    fun `thousands separators survive`() {
        assertEquals(120_050L, AmountSniffer.sniff("Your balance is ETB 1,200.50"))
    }

    @Test
    fun `amharic currency is recognised`() {
        assertEquals(45_000L, AmountSniffer.sniff("ብር 450.00 ደርሶዎታል"))
    }

    @Test
    fun `numbers that are not money are not mistaken for it`() {
        // The reason this is anchored to a currency word rather than taking the first
        // number: messages are full of account numbers, dates and reference codes.
        assertNull(AmountSniffer.sniff("Your account 1********0001 was accessed on 14/03 at 09:22"))
        assertNull(AmountSniffer.sniff("Your OTP is 483920"))
    }

    @Test
    fun `a message with no amount at all gives nothing`() {
        assertNull(AmountSniffer.sniff("Dear customer, beware of fraud. Never share your PIN."))
    }

    @Test
    fun `the first amount wins when a message carries several`() {
        // CBE states the amount, then a charge, then VAT, then a total, then a balance.
        // The first is the transaction; the rest are commentary on it.
        val body = "A debit transaction of ETB 2000.0 has occurred. Service charge of " +
            "ETB 10.00 and VAT of ETB1.50, total ETB2011.50. Balance is ETB22.92."

        assertEquals(200_000L, AmountSniffer.sniff(body))
    }

    @Test
    fun `totals skip the messages with nothing to find`() {
        val bodies = listOf(
            "You have received ETB 450.00",
            "Dear customer, beware of fraud.",
            "You have received ETB 12.50",
        )

        assertEquals(46_250L, AmountSniffer.sniffTotal(bodies))
        assertEquals(2, AmountSniffer.sniffedCount(bodies))
    }

    @Test
    fun `an empty list totals nothing`() {
        assertEquals(0L, AmountSniffer.sniffTotal(emptyList()))
        assertEquals(0, AmountSniffer.sniffedCount(emptyList()))
    }
}
