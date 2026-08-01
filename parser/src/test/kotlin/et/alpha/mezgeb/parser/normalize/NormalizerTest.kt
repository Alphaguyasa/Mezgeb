package et.alpha.mezgeb.parser.normalize

import et.alpha.mezgeb.parser.model.Direction
import et.alpha.mezgeb.parser.model.Channel
import et.alpha.mezgeb.parser.rule.DirectionKeywords
import et.alpha.mezgeb.parser.rule.DirectionStrategy
import et.alpha.mezgeb.parser.rule.ParserRule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AmountNormalizerTest {

    @Test
    fun `parses the formats the spec names`() {
        assertEquals(50000, AmountNormalizer.toMinor("500"))
        assertEquals(50050, AmountNormalizer.toMinor("500.5"))
        assertEquals(50050, AmountNormalizer.toMinor("500.50"))
        assertEquals(123456, AmountNormalizer.toMinor("1,234.56"))
        assertEquals(123456, AmountNormalizer.toMinor("1 234.56"))
        assertEquals(123456, AmountNormalizer.toMinor("ETB 1,234.56"))
        assertEquals(123456, AmountNormalizer.toMinor("1,234.56 ETB"))
        assertEquals(50000, AmountNormalizer.toMinor("Birr 500"))
        assertEquals(1045075, AmountNormalizer.toMinor("10,450.75"))
    }

    @Test
    @DisplayName("a single decimal place is tenths of a birr, not hundredths")
    fun `one decimal place pads on the right`() {
        // 500.5 birr is 500 birr 50 santim. Padding on the wrong side would make it
        // 500 birr 05 santim and lose 45 santim per transaction.
        assertEquals(50050, AmountNormalizer.toMinor("500.5"))
        assertEquals(200000, AmountNormalizer.toMinor("2000.0"))
        assertEquals(700, AmountNormalizer.toMinor("7.0"))
    }

    @Test
    @DisplayName("no floating point: 500.10 is exactly 50010")
    fun `avoids the double rounding trap`() {
        // (500.10 * 100).toLong() is 50009 on some inputs. String parsing cannot drift.
        assertEquals(50010, AmountNormalizer.toMinor("500.10"))
        assertEquals(1000010, AmountNormalizer.toMinor("10,000.10"))
        assertEquals(70294, AmountNormalizer.toMinor("702.94"))
    }

    @Test
    @DisplayName("a trailing sentence period is not part of the amount")
    fun `strips a trailing period`() {
        // Observed verbatim in CBE: "of ETB 2000.0. has occurred".
        assertEquals(200000, AmountNormalizer.toMinor("2000.0."))
        assertEquals(2700, AmountNormalizer.toMinor("27.0."))
    }

    @Test
    fun `handles ETB bound directly to the digits`() {
        assertEquals(2700, AmountNormalizer.toMinor("ETB27.00"))
        assertEquals(279025, AmountNormalizer.toMinor("ETB2,790.25"))
    }

    @Test
    fun `handles the Amharic currency word`() {
        assertEquals(50000, AmountNormalizer.toMinor("ብር 500"))
        assertEquals(7550, AmountNormalizer.toMinor("75.50 ብር"))
    }

    @Test
    fun `more than two decimal places truncate rather than round`() {
        // Truncation is deliberate: rounding up would invent money.
        assertEquals(50099, AmountNormalizer.toMinor("500.999"))
    }

    @Test
    fun `returns null when there is no amount`() {
        assertNull(AmountNormalizer.toMinor(null))
        assertNull(AmountNormalizer.toMinor(""))
        assertNull(AmountNormalizer.toMinor("   "))
        assertNull(AmountNormalizer.toMinor("no digits here"))
    }
}

class PhoneNormalizerTest {

    @Test
    fun `normalises every Ethiopian mobile form to E164`() {
        assertEquals("+251912345678", PhoneNormalizer.toE164("0912345678"))
        assertEquals("+251912345678", PhoneNormalizer.toE164("+251912345678"))
        assertEquals("+251912345678", PhoneNormalizer.toE164("251912345678"))
        assertEquals("+251912345678", PhoneNormalizer.toE164("912345678"))
        assertEquals("+251712345678", PhoneNormalizer.toE164("0712345678"))
    }

    @Test
    fun `tolerates separators and surrounding whitespace`() {
        assertEquals("+251912345678", PhoneNormalizer.toE164(" 091-234-5678 "))
        assertEquals("+251912345678", PhoneNormalizer.toE164("+251 91 234 5678"))
    }

    @Test
    fun `rejects anything that is not an Ethiopian mobile number`() {
        assertNull(PhoneNormalizer.toE164("127"))          // shortcode
        assertNull(PhoneNormalizer.toE164("0112345678"))   // landline prefix
        assertNull(PhoneNormalizer.toE164("12345"))
        assertNull(PhoneNormalizer.toE164(null))
        assertNull(PhoneNormalizer.toE164(""))
    }
}

class GeezNumeralNormalizerTest {

    @Test
    fun `converts unit and tens symbols`() {
        assertEquals("1", GeezNumeralNormalizer.toArabicDigits("፩"))
        assertEquals("9", GeezNumeralNormalizer.toArabicDigits("፱"))
        assertEquals("10", GeezNumeralNormalizer.toArabicDigits("፲"))
        assertEquals("90", GeezNumeralNormalizer.toArabicDigits("፺"))
    }

    @Test
    @DisplayName("hundreds multiply what precedes them")
    fun `handles the multiplicative hundred`() {
        assertEquals("100", GeezNumeralNormalizer.toArabicDigits("፻"))
        assertEquals("200", GeezNumeralNormalizer.toArabicDigits("፪፻"))
        // (20 x 100) + 30 + 4
        assertEquals("2034", GeezNumeralNormalizer.toArabicDigits("፳፻፴፬"))
    }

    @Test
    fun `leaves non-Geez text untouched`() {
        assertEquals("ETB 500.00", GeezNumeralNormalizer.toArabicDigits("ETB 500.00"))
        assertEquals("balance", GeezNumeralNormalizer.toArabicDigits("balance"))
    }

    @Test
    fun `converts Geez embedded in Amharic text`() {
        assertEquals("ብር 100 ተቀብለዋል", GeezNumeralNormalizer.toArabicDigits("ብር ፻ ተቀብለዋል"))
    }

    @Test
    @DisplayName("feeds the amount normalizer")
    fun `amounts written in Geez parse`() {
        assertEquals(10000, AmountNormalizer.toMinor("ብር ፻"))
        assertEquals(20000, AmountNormalizer.toMinor("፪፻ ብር"))
    }
}

class DateNormalizerTest {

    private fun epochOf(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    @DisplayName("a timestamp with no zone is East Africa Time")
    fun `assumes EAT when no offset is given`() {
        assertEquals(epochOf("2026-07-30T16:49:24+03:00"), DateNormalizer.parse("2026-07-30 16:49:24"))
        assertEquals(epochOf("2026-07-30T12:38:30+03:00"), DateNormalizer.parse("30/07/26 12:38:30"))
        assertEquals(epochOf("2026-07-30T16:50:54+03:00"), DateNormalizer.parse("30/07/2026 16:50:54"))
    }

    @Test
    fun `an explicit offset wins over the EAT assumption`() {
        assertEquals(epochOf("2026-07-14T09:22:00+03:00"), DateNormalizer.parse("2026-07-14T09:22:00+03:00"))
        assertEquals(epochOf("2026-07-14T09:22:00Z"), DateNormalizer.parse("2026-07-14T09:22:00Z"))
    }

    @Test
    @DisplayName("CBE writes 'dd/MM/yyyy at HH:mm:ss'")
    fun `tolerates the word at between date and time`() {
        assertEquals(epochOf("2026-06-29T19:00:33+03:00"), DateNormalizer.parse("29/06/2026 at 19:00:33"))
    }

    @Test
    fun `falls back to receivedAt when the body has no timestamp`() {
        val receivedAt = 1_785_000_000_000L
        assertEquals(receivedAt, DateNormalizer.toEpochMillis(null, receivedAt))
        assertEquals(receivedAt, DateNormalizer.toEpochMillis("not a date", receivedAt))
    }

    @Test
    fun `parses a date with no time as midnight EAT`() {
        assertEquals(epochOf("2026-07-30T00:00:00+03:00"), DateNormalizer.parse("2026-07-30"))
    }
}

class DirectionResolverTest {

    private fun rule(
        strategy: DirectionStrategy,
        keywords: DirectionKeywords? = null,
    ) = ParserRule(
        id = "t", name = "t", senderPattern = "T", channel = Channel.OTHER,
        bodyPattern = ".*", fieldMap = emptyMap(),
        directionStrategy = strategy, directionKeywords = keywords,
    )

    @Test
    fun `fixed strategies ignore the body entirely`() {
        assertEquals(Direction.IN, DirectionResolver.resolve(rule(DirectionStrategy.FIXED_IN), "sent", null))
        assertEquals(Direction.OUT, DirectionResolver.resolve(rule(DirectionStrategy.FIXED_OUT), "received", null))
    }

    @Test
    @DisplayName("keywords come from rule data, never from this file")
    fun `keyword strategy uses the rule's own words`() {
        val r = rule(
            DirectionStrategy.KEYWORD,
            DirectionKeywords(inbound = listOf("received"), outbound = listOf("sent")),
        )
        assertEquals(Direction.IN, DirectionResolver.resolve(r, "you have received ETB 5", null))
        assertEquals(Direction.OUT, DirectionResolver.resolve(r, "you sent ETB 5", null))
    }

    @Test
    @DisplayName("an Amharic template is a data change, not a code change")
    fun `keyword strategy works in Amharic`() {
        val r = rule(
            DirectionStrategy.KEYWORD,
            DirectionKeywords(inbound = listOf("ተቀብለዋል"), outbound = listOf("ልከዋል")),
        )
        assertEquals(Direction.IN, DirectionResolver.resolve(r, "ብር 100 ተቀብለዋል", null))
        assertEquals(Direction.OUT, DirectionResolver.resolve(r, "ብር 100 ልከዋል", null))
    }

    @Test
    fun `returns null rather than guessing when no keyword matches`() {
        val r = rule(
            DirectionStrategy.KEYWORD,
            DirectionKeywords(inbound = listOf("received"), outbound = listOf("sent")),
        )
        assertNull(DirectionResolver.resolve(r, "something else entirely", null))
    }

    @Test
    fun `the more specific keyword wins when both appear`() {
        val r = rule(
            DirectionStrategy.KEYWORD,
            DirectionKeywords(inbound = listOf("transferred to you"), outbound = listOf("transferred")),
        )
        assertEquals(Direction.IN, DirectionResolver.resolve(r, "money transferred to you today", null))
    }
}

class SenderNormalizerTest {

    @Test
    fun `uppercases and trims`() {
        assertEquals("CBE", SenderNormalizer.normalize(" cbe "))
        assertEquals("EBIRR", SenderNormalizer.normalize("EBirr"))
    }

    @Test
    fun `strips carrier decoration from alphabetic senders`() {
        assertEquals("CBE", SenderNormalizer.normalize("SMS from CBE"))
        assertEquals("CBE", SenderNormalizer.normalize("+251CBE"))
        assertEquals("CBE", SenderNormalizer.normalize("CBE-INFO"))
    }

    @Test
    @DisplayName("a numeric shortcode is left alone")
    fun `does not mangle shortcodes`() {
        // 127 is telebirr's transactional sender; stripping "251" from a numeric id
        // would corrupt senders like 251994.
        assertEquals("127", SenderNormalizer.normalize("127"))
        assertEquals("251994", SenderNormalizer.normalize("251994"))
    }
}
