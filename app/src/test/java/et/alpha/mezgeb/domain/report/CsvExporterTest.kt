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

    // --- Formula injection: free text must never run in a spreadsheet ---------------

    @Test
    fun `a value a spreadsheet would run as a formula gets a leading quote`() {
        assertEquals("'=HYPERLINK(\"x\",\"y\")", CsvExporter.text("=HYPERLINK(\"x\",\"y\")"))
        assertEquals("'+251900000000", CsvExporter.text("+251900000000"))
        assertEquals("'-2+3", CsvExporter.text("-2+3"))
        assertEquals("'@SUM(A1)", CsvExporter.text("@SUM(A1)"))
        // Some spreadsheets skip a leading tab or carriage return and read what follows.
        assertEquals("'\t=1+1", CsvExporter.text("\t=1+1"))
        assertEquals("'\r=1+1", CsvExporter.text("\r=1+1"))
    }

    @Test
    fun `plain text and amharic are unchanged`() {
        assertEquals("ABEBE", CsvExporter.text("ABEBE"))
        assertEquals("አበበ በቀለ", CsvExporter.text("አበበ በቀለ"))
        assertEquals("0911000000", CsvExporter.text("0911000000"))
        // Only the first character counts; a formula sign later in a note is harmless.
        assertEquals("paid 200 = half", CsvExporter.text("paid 200 = half"))
        assertEquals("", CsvExporter.text(""))
    }

    @Test
    fun `every free-text ledger column is neutralised, and the amount is not`() {
        val csv = CsvExporter.ledger(
            transactions = listOf(
                transaction(
                    amountMinor = 45_000,
                    counterpartyName = "=cmd|' /C calc'!A0",
                    counterpartyPhone = "+251900000000",
                    providerTxnId = "@REF",
                    categoryId = "cat_bad",
                    note = "-1+1",
                ),
            ),
            categoryNames = mapOf("cat_bad" to "=1+1"),
            zone = zone,
        )

        val fields = splitFields(csv.trim().lineSequence().last())
        assertEquals(CsvExporter.LEDGER_COLUMNS.size, fields.size)
        val byColumn = CsvExporter.LEDGER_COLUMNS.zip(fields).toMap()
        assertEquals("'=cmd|' /C calc'!A0", byColumn["counterparty"])
        assertEquals("'+251900000000", byColumn["phone"])
        assertEquals("'@REF", byColumn["reference"])
        assertEquals("'=1+1", byColumn["category"])
        assertEquals("'-1+1", byColumn["note"])
        assertEquals("450.00", byColumn["amount"])
    }

    @Test
    fun `the credit book neutralises the customer's name, phone and note`() {
        val csv = CsvExporter.creditBook(
            entries = listOf(
                CreditEntry(
                    id = "e1",
                    customerId = "c1",
                    kind = CreditKind.REPAYMENT,
                    amountMinor = 10_000,
                    occurredAt = T0,
                    note = "=IMPORTXML(\"x\", \"y\")",
                    linkedTransactionId = null,
                ),
            ),
            customers = mapOf("c1" to Customer("c1", "@Abebe", "+251900000001", null, T0)),
            zone = zone,
        )

        val fields = splitFields(csv.trim().lineSequence().last())
        val byColumn = CsvExporter.CREDIT_COLUMNS.zip(fields).toMap()
        assertEquals("'@Abebe", byColumn["customer"])
        assertEquals("'+251900000001", byColumn["phone"])
        assertEquals("'=IMPORTXML(\"x\", \"y\")", byColumn["note"])
        assertEquals("100.00", byColumn["amount"])
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

    /** Splits a row the way a reader would: quotes respected, doubled quotes undone. */
    private fun splitFields(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val char = row[i]
            when {
                char == '"' && inQuotes && i + 1 < row.length && row[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            i++
        }
        fields += current.toString()
        return fields
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
        counterpartyPhone: String? = null,
        providerTxnId: String? = null,
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
        counterpartyPhone = counterpartyPhone,
        providerTxnId = providerTxnId,
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
