package et.alpha.mezgeb.navigation

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ROADMAP R49: the intents that open the app on a given screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchRequestTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the reconcile intent names the app's own activity and reaches the running one`() {
        val intent = LaunchRequest.openReconcile(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        // Handed to the activity already open, not a second copy of the app on top.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        // Nothing but the action travels.
        assertNull(intent.extras)
        assertEquals(MezgebDestination.Reconcile, LaunchRequest.destinationFor(intent))
    }

    @Test
    fun `an ordinary launch asks for no screen`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        assertNull(LaunchRequest.destinationFor(launcher))
        assertNull(LaunchRequest.destinationFor(Intent("com.example.SOMETHING_ELSE")))
        assertNull(LaunchRequest.destinationFor(null))
    }
}
