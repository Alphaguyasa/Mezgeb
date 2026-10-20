package et.alpha.mezgeb.capture.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `direct` half of the permission flow: what the import asks for, and that the
 * manifest can grant it.
 *
 * A runtime request for a permission the manifest does not declare is refused by the
 * system without showing a dialog. The user taps "Read past messages", nothing
 * happens, and no log says why. So every name the importer hands to the onboarding
 * screen must also be in the merged manifest. `OnboardingPermissionFlowTest` covers
 * what the screen does with the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectSmsImporterPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val importer = DirectSmsImporter(context)

    @Test
    fun `the direct build imports and asks only to read messages`() {
        assertTrue(importer.isSupported)
        // READ_SMS only. Receiving messages is the notification listener's job, and
        // RECEIVE_SMS is never requested (MergedManifestPermissionTest).
        assertEquals(listOf(Manifest.permission.READ_SMS), importer.requiredPermissions())
    }

    @Test
    fun `every permission the import asks for is declared in the manifest`() {
        @Suppress("DEPRECATION") // The flags overload is API 33+; this reads the same data.
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

        val undeclared = importer.requiredPermissions().filterNot { it in declared }
        assertEquals("requested at runtime but not declared", emptyList<String>(), undeclared)
    }
}
