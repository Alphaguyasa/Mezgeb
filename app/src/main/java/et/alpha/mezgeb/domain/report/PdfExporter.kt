package et.alpha.mezgeb.domain.report

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.R
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import java.io.OutputStream
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/**
 * A one-page period summary as a PDF.
 *
 * `PdfDocument` from the framework rather than a library: the page is a header, eight
 * numbers and a short table, and every PDF library that would draw it also brings a
 * font subsystem this app does not need.
 *
 * **The Ethiopic font is the whole difficulty.** `Paint`'s default typeface renders
 * Amharic as boxes in a PDF on many devices, because the system font that covers
 * Ethiopic is not the one PDF rendering picks up. The app already bundles Noto Sans
 * Ethiopic for exactly this reason (Session 1), so the same file is loaded here. A
 * shop called መዝገብ must not export a receipt with its own name in tofu.
 */
class PdfExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val zone: ZoneId,
) {

    fun write(report: Report, shopName: String, generatedAt: Long, output: OutputStream) {
        val document = PdfDocument()
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create(),
        )

        val canvas = page.canvas
        val ethiopic = runCatching {
            ResourcesCompat.getFont(context, R.font.noto_sans_ethiopic)
        }.getOrNull() ?: Typeface.DEFAULT

        val title = paint(ethiopic, size = 22f, bold = true)
        val heading = paint(ethiopic, size = 13f, bold = true)
        val body = paint(ethiopic, size = 11f)
        val quiet = paint(ethiopic, size = 9f).apply { alpha = 150 }

        var y = MARGIN + 24f

        canvas.drawText(shopName, MARGIN, y, title)
        y += 20f
        canvas.drawText(
            context.getString(
                R.string.report_pdf_period,
                MezgebDates.formatFullDate(report.periodStart, zone, Locale.getDefault()),
                MezgebDates.formatFullDate(report.periodEnd, zone, Locale.getDefault()),
            ),
            MARGIN,
            y,
            body,
        )

        y += 32f
        canvas.drawText(context.getString(R.string.report_summary), MARGIN, y, heading)
        y += 18f
        y = drawRow(canvas, context.getString(R.string.today_total_in), report.totalInMinor, y, body)
        y = drawRow(canvas, context.getString(R.string.today_total_out), report.totalOutMinor, y, body)
        y = drawRow(canvas, context.getString(R.string.today_net), report.netMinor, y, body)
        canvas.drawText(
            context.getString(R.string.report_transaction_count, report.transactionCount),
            MARGIN,
            y,
            body,
        )

        y += 32f
        canvas.drawText(context.getString(R.string.report_by_category), MARGIN, y, heading)
        y += 18f
        report.byCategory.forEach { slice ->
            canvas.drawText(slice.label, MARGIN, y, body)
            canvas.drawText(
                MoneyFormatter.format(slice.amountMinor) + "   " + percent(slice.share),
                VALUE_COLUMN,
                y,
                body,
            )
            y += 16f
        }

        // The generation stamp goes at the foot because a printed summary outlives the
        // screen it came from, and "as of when" is the first thing anyone asks of one.
        canvas.drawText(
            context.getString(
                R.string.report_pdf_generated,
                MezgebDates.formatFullDate(generatedAt, zone, Locale.getDefault()),
                MezgebDates.formatTime(generatedAt, zone, Locale.getDefault()),
            ),
            MARGIN,
            PAGE_HEIGHT - MARGIN,
            quiet,
        )

        document.finishPage(page)
        document.writeTo(output)
        document.close()
    }

    private fun drawRow(
        canvas: android.graphics.Canvas,
        label: String,
        amountMinor: Long,
        y: Float,
        paint: Paint,
    ): Float {
        canvas.drawText(label, MARGIN, y, paint)
        canvas.drawText(MoneyFormatter.format(amountMinor), VALUE_COLUMN, y, paint)
        return y + 16f
    }

    private fun paint(typeface: Typeface, size: Float, bold: Boolean = false) = Paint().apply {
        this.typeface = if (bold) Typeface.create(typeface, Typeface.BOLD) else typeface
        textSize = size
        isAntiAlias = true
    }

    private fun percent(share: Float): String = "%.0f%%".format(share * 100)

    companion object {
        const val MIME_TYPE = "application/pdf"

        /** A4 at 72dpi, which is what `PdfDocument` measures in. */
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 40f
        private const val VALUE_COLUMN = 300f

        fun fileName(at: Long, zone: ZoneId): String =
            "mezgeb-summary-" + MezgebDates.localDate(at, zone) + ".pdf"
    }
}
