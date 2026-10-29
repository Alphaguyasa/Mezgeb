package et.alpha.mezgeb.domain.report

import et.alpha.mezgeb.domain.model.CreditEntry
import et.alpha.mezgeb.domain.model.Customer
import et.alpha.mezgeb.domain.model.Transaction
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The ledger and the credit book as CSV.
 *
 * Two decisions here are load-bearing and neither is obvious:
 *
 * **The byte-order mark.** Excel on Windows opens a UTF-8 file as the system code page
 * unless the file starts with a BOM, which turns every Amharic name into mojibake. The
 * BOM is three bytes of ugliness that make the difference between a usable export and
 * an unreadable one for the users this app is for.
 *
 * **Amounts are plain decimals, not grouped.** `MoneyFormatter.format` would write
 * `1,200.50`, and a comma inside a number in a comma-separated file is a bug waiting
 * for a spreadsheet. Money stays `Long` santim everywhere else in this app; this is
 * the boundary where it becomes text, and it becomes the least ambiguous text
 * available.
 *
 * **Free text cannot start a formula.** A name, a note or a reference comes from a
 * message or from the owner's typing, and Excel or Sheets runs a cell that starts with
 * `=`, `+`, `-` or `@` as a formula (`=HYPERLINK(...)` is the classic). Every free-text
 * cell goes through [text], which puts a `'` in front of such a value. Amounts and
 * dates do not: they are written by this file, and a negative amount must stay a
 * number.
 */
object CsvExporter {

    /**
     * Excel needs this to read the file as UTF-8.
     *
     * Written as an escape rather than as the character itself: a literal BOM sitting
     * in the middle of a source file is invisible in every editor and is the kind of
     * thing a later reformat silently deletes.
     */
    const val BYTE_ORDER_MARK: String = "\uFEFF"

    val LEDGER_COLUMNS = listOf(
        "date", "direction", "amount", "currency", "channel",
        "counterparty", "phone", "reference", "category", "note", "reconciled",
    )

    val CREDIT_COLUMNS = listOf(
        "date", "customer", "phone", "kind", "amount", "currency", "note", "linked_payment",
    )

    fun ledger(
        transactions: List<Transaction>,
        categoryNames: Map<String, String>,
        zone: ZoneId,
    ): String = buildCsv(LEDGER_COLUMNS) { append ->
        transactions.forEach { transaction ->
            append(
                listOf(
                    timestamp(transaction.occurredAt, zone),
                    transaction.direction.name,
                    MoneyFormatter.formatPlain(transaction.amountMinor),
                    transaction.currency,
                    transaction.channel.name,
                    text(transaction.counterpartyName.orEmpty()),
                    text(transaction.counterpartyPhone.orEmpty()),
                    text(transaction.providerTxnId.orEmpty()),
                    text(transaction.categoryId?.let { categoryNames[it] }.orEmpty()),
                    text(transaction.note.orEmpty()),
                    transaction.isReconciled.toString(),
                ),
            )
        }
    }

    fun creditBook(
        entries: List<CreditEntry>,
        customers: Map<String, Customer>,
        zone: ZoneId,
    ): String = buildCsv(CREDIT_COLUMNS) { append ->
        entries.forEach { entry ->
            val customer = customers[entry.customerId]
            append(
                listOf(
                    timestamp(entry.occurredAt, zone),
                    text(customer?.name.orEmpty()),
                    text(customer?.phone.orEmpty()),
                    entry.kind.name,
                    MoneyFormatter.formatPlain(entry.amountMinor),
                    MoneyFormatter.DEFAULT_CURRENCY,
                    text(entry.note.orEmpty()),
                    entry.linkedTransactionId.orEmpty(),
                ),
            )
        }
    }

    /**
     * RFC 4180 quoting: wrap when the value contains a delimiter, a quote or a line
     * break, and double any quote inside. A shop's note field is free text and will
     * eventually contain all three.
     */
    fun escape(value: String): String {
        val needsQuoting = value.any { it == DELIMITER || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /**
     * Neutralises a free-text value that a spreadsheet would run as a formula, by
     * prefixing `'` (the OWASP advice for CSV injection). A tab or a carriage return at
     * the start is included because some spreadsheets skip it and then read the
     * formula behind it. The cost is visible: a phone exported as `+251...` reads
     * `'+251...` in a plain text editor. That is the price of not running a stranger's
     * formula on the accountant's computer.
     */
    fun text(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_TRIGGERS) "'$value" else value

    private fun buildCsv(
        columns: List<String>,
        rows: ((List<String>) -> Unit) -> Unit,
    ): String = buildString {
        append(BYTE_ORDER_MARK)
        appendLine(columns.joinToString(DELIMITER.toString()))
        rows { values -> appendLine(values.joinToString(DELIMITER.toString()) { escape(it) }) }
    }

    /** ISO-8601 with the offset, so a spreadsheet and a human agree on what day it was. */
    private fun timestamp(epochMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(epochMillis).atZone(zone),
        )

    private const val DELIMITER = ','

    private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')

    /** Kept so callers naming a file agree on the extension and type. */
    const val MIME_TYPE = "text/csv"

    fun fileName(prefix: String, at: Long, zone: ZoneId): String =
        "$prefix-" + MezgebDates.localDate(at, zone) + ".csv"
}
