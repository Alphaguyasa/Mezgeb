package et.alpha.mezgeb.domain.report

import et.alpha.mezgeb.data.local.T0
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * What can honestly be tested off a device, which is less than it looks.
 *
 * `PdfDocument` is a thin wrapper over native code: `nativeCreateDocument()` returns 0
 * under Robolectric, so the very first `startPage` throws "document is closed!". There
 * is no shadow for it, and writing one would be testing a fake rather than the
 * exporter.
 *
 * So the rendering — including whether Ethiopic comes out as glyphs or as boxes, which
 * is the failure this exporter exists to avoid — is on the device checklist in
 * NOTES.md, not asserted here. A test that cannot run is worse than an admission that
 * it cannot: the first looks like coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfExporterTest {

    private val zone: ZoneId = ZoneId.of("Africa/Addis_Ababa")

    @Test
    fun `the file name carries the date it was made`() {
        assertEquals("mezgeb-summary-2026-07-02.pdf", PdfExporter.fileName(T0, zone))
    }

    @Test
    fun `the mime type is one a share sheet will recognise`() {
        assertEquals("application/pdf", PdfExporter.MIME_TYPE)
    }

}
