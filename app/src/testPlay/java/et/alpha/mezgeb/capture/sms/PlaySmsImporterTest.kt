package et.alpha.mezgeb.capture.sms

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `play` half of the permission flow: this build asks for nothing.
 *
 * The onboarding screen requests exactly what the importer lists, so an entry here
 * would put a runtime permission dialog in front of a Play user for a permission the
 * play manifest does not declare. The system would refuse it without asking, and the
 * import button would look broken. `OnboardingPermissionFlowTest` covers the screen.
 */
class PlaySmsImporterTest {

    private val importer = PlaySmsImporter()

    @Test
    fun `the play build cannot import and asks for no permission`() {
        assertFalse(importer.isSupported)
        assertTrue(importer.requiredPermissions().isEmpty())
    }

    @Test
    fun `the play build only ever reports that import is unavailable`() = runTest {
        // Start does nothing, so the status stays put and the screen hides the offer.
        importer.start()

        assertEquals(listOf(SmsImportStatus.Unavailable), importer.observeStatus().toList())
    }
}
