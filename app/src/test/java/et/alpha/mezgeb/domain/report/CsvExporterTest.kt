package et.alpha.mezgeb.domain.report

import et.alpha.mezgeb.data.local.T0
import et.alpha.mezgeb.domain.model.Channel
import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.CreditKind
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.Direction
import et.alpha.mezgeb.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CsvExporterTest {

    private val zone: ZoneId = ZoneId.of("Africa/Addis_Ababa")

    // --- Escaping, which is the part that has to be right ----------------------------

    @Test
    fun `ordinary values are left alone`() {
        assertEquals("ABEBE", CsvExporter.escape("ABEBE"))
        assertEquals("450.00", CsvExporter.escape("450.00"))
        assertEquals("", CsvExporter.escape(""))
    }

    @Test
    fun `a value containing a comma is quoted`() {
        assertEquals("\"Abebe, Bekele\"", CsvExporter.escape("Abebe, Bekele"))
    }

    @Test
    fun `a quote is doubled and the value is wrapped`() {
        assertEquals("\"he said \"\"paid\"\"\"", CsvExporter.escape("he said \"paid\""))
    }

    @Test
    fun `a newline inside a note does not break the row`() {
        // A shop's note field is free text and will eventually contain one.
        assertEquals("\"first line\nsecond line\"", CsvExporter.escape("first line\nsecond line"))
        assertEquals("\"carriage\rreturn\"", CsvExporter.escape("carriage\rreturn"))
    }

    @Test
    fun `all three at once`() {
        val nasty = "note with, a comma, a \"quote\" and\na newline"

        val escaped = CsvExporter.escape(nasty)

        assertTrue(escaped.startsWith("\"") && escaped.endsWith("\""))
        // Unwrapping and un-doubling gets the original back, which is the whole
        // contract a reader depends on.
        assertEquals(nasty, escaped.removeSurrounding("\"").replace("\"\"", "\""))
    }

    @Test
    fun `amharic text is not escaped, only encoded`() {
        assertEquals("አበበ በቀለ", CsvExporter.escape("አበበ በቀለ"))
    }

    // --- The files ---------------------------------------------------------------------

    @Test
    fun `the ledger starts with a byte order mark so excel reads amharic`() {
        val csv = CsvExporter.ledger(emptyList(), emptyMap(), zone)

        // Three bytes of ugliness that decide whether the export is readable at all
        // for the users this app is for.
        assertTrue(csv.startsWith("﻿"))
    }

    @Test
    fun `the ledger has the columns the session brief asks for, in order`() {
        val csv = CsvExporter.ledger(emptyList(), emptyMap(), zone)
        val header = csv.removePrefix("﻿").lineSequence().first()

        assertEquals(
            "date,direction,amount,currency,channel,counterparty,phone,reference," +
                "category,note,reconciled",
            header,
        )
    }

    @Test
    fun `a transaction becomes one row with its category name, not its id`() {
        val csv = CsvExporter.ledger(
            transactions = listOf(
                transaction(
                    amountMinor = 120_050,
                    counterpartyName = "ABEBE",
                    categoryId = "cat_sales",
                    note = "market day",
                ),
            ),
            categoryNames = mapOf("cat_sales" to "Sales"),
            zone = zone,
        )

        val row = csv.trim().lineSequence().last()
        assertTrue("category name expected, got: $row", row.contains(",Sales,"))
        // Grouped thousands would put a comma inside a number in a comma-separated
        // file, which is a bug waiting for a spreadsheet.
        assertTrue("amount must not be grouped: $row", row.contains(",1200.50,"))
        assertTrue(row.contains("ABEBE"))
    }

    @Test
    fun `a note containing a comma keeps the row's column count`() {
        val csv = CsvExporter.ledger(
            transactions = listOf(transaction(note = "paid 200, owes 250")),
            categoryNames = emptyMap(),
            zone = zone,
        )

        val row = csv.trim().lineSequence().last()
        assertEquals(CsvExporter.LEDGER_COLUMNS.size, countFields(row))
    }

    @Test
    fun `the credit book exports customer names alongside entries`() {
        val csv = CsvExporter.creditBook(
            entries = listOf(
                CreditEntry(
                    id = "e1",
                    customerId = "c1",
                    kind = CreditKind.CREDIT_GIVEN,
                    amountMinor = 50_000,
                    occurredAt = T0,
                    note = null,
                    linkedTransactionId = null,
                ),
            ),
            customers = mapOf(
                "c1" to Customer("c1", "Abebe, Bekele", "+251911000001", null, T0),
            ),
            zone = zone,
        )

        val row = csv.trim().lineSequence().last()
        assertTrue(row.contains("\"Abebe, Bekele\""))
        assertTrue(row.contains("CREDIT_GIVEN"))
        assertTrue(row.contains("500.00"))
    }

    @Test
    fun `dates carry their offset so a spreadsheet and a human agree on the day`() {
        val csv = CsvExporter.ledger(
            transactions = listOf(transaction(occurredAt = T0)),
            categoryNames = emptyMap(),
            zone = zone,
        )

        val row = csv.trim().lineSequence().last()
        assertTrue("expected an ISO offset timestamp: $row", row.startsWith("2026-07-02T03:00:00+03:00"))
    }

    /** Counts fields the way a reader would, respecting quotes. */
    private fun countFields(row: String): Int {
        var fields = 1
        var inQuotes = false
        row.forEach { char ->
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> fields++
            }
        }
        return fields
    }

    private fun transaction(
        amountMinor: Long = 45_000,
        counterpartyName: String? = null,
        categoryId: String? = null,
        note: String? = null,
        occurredAt: Long = T0,
    ) = Transaction(
        id = "t1",
        rawMessageId = null,
        direction = Direction.IN,
        amountMinor = amountMinor,
        currency = "ETB",
        channel = Channel.TELEBIRR,
        counterpartyName = counterpartyName,
        counterpartyPhone = null,
        providerTxnId = null,
        balanceAfterMinor = null,
        occurredAt = occurredAt,
        categoryId = categoryId,
        note = note,
        isReconciled = false,
        reconciliationSessionId = null,
        isDeleted = false,
        editedAt = null,
    )
}
