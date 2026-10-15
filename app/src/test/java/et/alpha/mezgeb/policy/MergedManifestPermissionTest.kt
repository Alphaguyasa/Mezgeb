package et.alpha.mezgeb.policy

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the built app actually requests, after every library's manifest is merged in.
 *
 * [ProjectRulesTest] reads the manifests this project writes. A dependency can add a
 * permission of its own, which is exactly how ACCESS_NETWORK_STATE arrived with
 * WorkManager, so the merged result is checked too. Runs once per flavor.
 *
 * A plain [Application], so the app's own startup work stays out of a test that only
 * reads the package.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MergedManifestPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION") // The flags overload is API 33+; this reads the same data.
    private val requested: Set<String> =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()

    private val isDirect = ".direct" in context.packageName

    @Test
    fun `neither flavor can reach the network`() {
        assertFalse(
            "INTERNET was merged in from somewhere",
            "android.permission.INTERNET" in requested,
        )
        assertFalse(
            "ACCESS_NETWORK_STATE is back; the main manifest removes it on purpose",
            "android.permission.ACCESS_NETWORK_STATE" in requested,
        )
    }

    @Test
    fun `only the direct flavor reads messages, and never receives them`() {
        assertFalse(
            "RECEIVE_SMS is never requested",
            "android.permission.RECEIVE_SMS" in requested,
        )
        val readsMessages = "android.permission.READ_SMS" in requested
        if (isDirect) {
            assertTrue("the direct build lost READ_SMS", readsMessages)
        } else {
            assertFalse("the play build requests READ_SMS", readsMessages)
        }
    }

    @Test
    fun `the permissions the app needs are still there`() {
        // Guards the test itself: an empty set would pass everything above.
        assertTrue(
            "POST_NOTIFICATIONS missing; is the merged manifest being read at all?",
            "android.permission.POST_NOTIFICATIONS" in requested,
        )
    }
}
