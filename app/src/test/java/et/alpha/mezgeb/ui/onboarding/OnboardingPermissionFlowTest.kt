package et.alpha.mezgeb.ui.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.capture.sms.SmsImportStatus
import et.alpha.mezgeb.capture.sms.SmsImporter
import et.alpha.mezgeb.capture.vendor.BatteryOptimizationHelper
import et.alpha.mezgeb.data.settings.SettingsHarness
import et.alpha.mezgeb.designsystem.MezgebTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The permission flow, driven through the real `OnboardingRoute`.
 *
 * The ViewModel test covers what the state says. What it cannot see is the part the
 * route does on its own: which permissions it hands to the system dialog, and what it
 * does with the answer. That decision lives in a launcher callback, and a mistake
 * there either starts an import the user refused or silently never starts one they
 * allowed. CONVENTIONS.md names permission flows as one of the two places a UI test
 * earns its keep.
 *
 * The system dialog itself is replaced by a recording [ActivityResultRegistry]. That
 * is the seam the activity library provides for this: the route's launcher is real,
 * only the "show the dialog, wait for a tap" step is not. The importer is a fake, so
 * this test runs the same in both flavors; what each flavor's real importer asks for
 * is checked in `src/testPlay` and `src/testDirect`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingPermissionFlowTest {

    @get:Rule(order = 0)
    val tempFolder = TemporaryFolder()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val access = FakeCaptureAccess()
    private val importer = FakeSmsImporter()
    private val registry = RecordingResultRegistry()
    private var finished = false
    private lateinit var settingsHarness: SettingsHarness

    @Before
    fun setUp() {
        settingsHarness = SettingsHarness(tempFolder.root)
    }

    @After
    fun tearDown() {
        settingsHarness.close()
    }

    @Test
    fun `a build that cannot import never asks for a permission`() {
        // The play flavor: no inbox import, so nothing to ask for and nothing to offer.
        importer.isSupported = false
        importer.permissions = emptyList()
        access.granted = true
        showRoute()
        awaitText(R.string.onboarding_access_on)

        assertTrue(nodes(R.string.onboarding_import_action).isEmpty())
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `tapping import asks for the importer's permissions and the notification one`() {
        showLoadedRoute()

        tapImport()

        val requested = registry.launches.single().second.toList()
        assertEquals(listOf(READ_PERMISSION, Manifest.permission.POST_NOTIFICATIONS), requested)
        // Nothing may start before the user has answered.
        assertFalse(importer.started)
    }

    @Test
    fun `granting the permissions starts the import`() {
        showLoadedRoute()
        tapImport()

        answer(READ_PERMISSION to true, Manifest.permission.POST_NOTIFICATIONS to true)

        assertTrue(importer.started)
    }

    @Test
    fun `refusing the read permission never starts the import`() {
        showLoadedRoute()
        tapImport()

        answer(READ_PERMISSION to false, Manifest.permission.POST_NOTIFICATIONS to true)

        assertFalse(importer.started)
    }

    @Test
    fun `refusing the read permission says so and offers app settings`() {
        // After "don't ask again" the system refuses without a dialog, so without this
        // message the button would look broken. The settings button is the way out.
        showLoadedRoute()
        tapImport()

        answer(READ_PERMISSION to false, Manifest.permission.POST_NOTIFICATIONS to true)
        awaitText(R.string.onboarding_import_refused)

        compose.onNodeWithText(text(R.string.onboarding_import_open_app_settings))
            .performScrollTo()
            .performClick()

        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(FakeCaptureAccess.APP_SETTINGS_ACTION, started?.action)
    }

    @Test
    fun `granting on a second try clears the refusal`() {
        showLoadedRoute()
        tapImport()
        answer(READ_PERMISSION to false)
        awaitText(R.string.onboarding_import_refused)

        tapImport()
        answer(READ_PERMISSION to true)

        assertTrue(importer.started)
        awaitNoText(R.string.onboarding_import_refused)
    }

    @Test
    fun `allowing the permission in app settings clears the refusal on return`() {
        showLoadedRoute()
        tapImport()
        answer(READ_PERMISSION to false)
        awaitText(R.string.onboarding_import_refused)

        // The user went to app settings and allowed it there; the route only learns
        // that by checking again when it comes back to the front.
        Shadows.shadowOf(context as Application).grantPermissions(READ_PERMISSION)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        awaitNoText(R.string.onboarding_import_refused)
        // Allowed is not the same as asked to import: nothing starts on its own.
        assertFalse(importer.started)
    }

    @Test
    fun `refusing only the notification permission still imports`() {
        // The progress notification is a nicety. Holding the import hostage to it would
        // punish a user for declining something the import does not need.
        showLoadedRoute()
        tapImport()

        answer(READ_PERMISSION to true, Manifest.permission.POST_NOTIFICATIONS to false)

        assertTrue(importer.started)
    }

    @Test
    fun `a dismissed dialog starts nothing`() {
        // Backing out of the system dialog comes back as an empty result, not a refusal.
        showLoadedRoute()
        tapImport()

        answer()

        assertFalse(importer.started)
        // Backing out is not a refusal, so there is nothing to explain.
        assertTrue(nodes(R.string.onboarding_import_refused).isEmpty())
    }

    @Test
    fun `an importer that needs no permission starts without a dialog`() {
        importer.permissions = emptyList()
        showLoadedRoute()

        tapImport()

        assertTrue(importer.started)
        assertTrue(registry.launches.isEmpty())
    }

    @Test
    fun `below Android 13 the notification permission is not requested`() {
        // POST_NOTIFICATIONS does not exist before API 33. Asking for it there is
        // answered "denied" by the platform, which is harmless only because the
        // import ignores that answer; leaving it out keeps the request honest.
        val realSdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
        val requested = try {
            withNotificationPermission(listOf(READ_PERMISSION)).toList()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", realSdk)
        }

        assertEquals(listOf(READ_PERMISSION), requested)
    }

    @Test
    fun `without notification access the button opens the system settings screen`() {
        access.granted = false
        showRoute()
        awaitText(R.string.onboarding_access_off)

        compose.onNodeWithText(text(R.string.onboarding_grant_action))
            .performScrollTo()
            .performClick()

        val started = Shadows.shadowOf(context as Application).nextStartedActivity
        assertEquals(FakeCaptureAccess.SETTINGS_ACTION, started?.action)
    }

    @Test
    fun `access granted in settings shows once the user comes back`() {
        // Notification access is granted in another app and has no callback. The
        // route re-reads it on resume, and this is the only test that would notice
        // that the resume hook was lost.
        access.granted = false
        showRoute()
        awaitText(R.string.onboarding_access_off)

        access.granted = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        awaitText(R.string.onboarding_access_on)
    }

    @Test
    fun `done leaves the screen`() {
        showLoadedRoute()

        compose.onNodeWithText(text(R.string.onboarding_done))
            .performScrollTo()
            .performClick()

        assertTrue(finished)
    }

    /**
     * Shows the route with access already granted and waits for the loaded state, so
     * the import card is on screen. "Access is on" is the visible sign that the state
     * has arrived; before it, the card would be missing for timing reasons only.
     */
    private fun showLoadedRoute() {
        access.granted = true
        showRoute()
        awaitText(R.string.onboarding_access_on)
    }

    private fun showRoute() {
        val viewModel = OnboardingViewModel(
            access = access,
            battery = BatteryOptimizationHelper(context),
            importer = importer,
            settings = settingsHarness.repository,
        )
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MezgebTheme {
                    OnboardingRoute(onFinished = { finished = true }, viewModel = viewModel)
                }
            }
        }
    }

    private fun tapImport() {
        compose.onNodeWithText(text(R.string.onboarding_import_action))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
    }

    /** Plays the user's answer to the latest system dialog back into the route's launcher. */
    private fun answer(vararg results: Pair<String, Boolean>) {
        val requestCode = registry.launches.last().first
        compose.runOnUiThread {
            registry.dispatchResult(requestCode, mapOf(*results))
        }
        compose.waitForIdle()
    }

    private fun awaitText(@StringRes id: Int) {
        compose.waitUntil(timeoutMillis = 5_000) { nodes(id).isNotEmpty() }
    }

    private fun awaitNoText(@StringRes id: Int) {
        compose.waitUntil(timeoutMillis = 5_000) { nodes(id).isEmpty() }
    }

    private fun nodes(@StringRes id: Int) =
        compose.onAllNodesWithText(text(id)).fetchSemanticsNodes()

    private fun text(@StringRes id: Int): String = context.getString(id)

    /**
     * Stands in for the system permission dialog. It records what was asked and
     * leaves the answer to the test, which is exactly what the real dialog does.
     */
    private class RecordingResultRegistry : ActivityResultRegistry() {
        val launches = mutableListOf<Pair<Int, Array<String>>>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            @Suppress("UNCHECKED_CAST")
            launches += requestCode to (input as Array<String>)
        }
    }

    private class FakeCaptureAccess(var granted: Boolean = false) : CaptureAccess {
        override fun isNotificationAccessGranted(): Boolean = granted
        override fun settingsIntent(): Intent = Intent(SETTINGS_ACTION)
        override fun appSettingsIntent(): Intent = Intent(APP_SETTINGS_ACTION)
        override fun requestRebind() = Unit

        companion object {
            const val SETTINGS_ACTION = "test.settings"
            const val APP_SETTINGS_ACTION = "test.app_settings"
        }
    }

    private class FakeSmsImporter(override var isSupported: Boolean = true) : SmsImporter {
        var permissions: List<String> = listOf(READ_PERMISSION)
        var started: Boolean = false

        override fun requiredPermissions(): List<String> = permissions
        override fun start() {
            started = true
        }

        override fun observeStatus(): Flow<SmsImportStatus> =
            MutableStateFlow(if (isSupported) SmsImportStatus.Idle else SmsImportStatus.Unavailable)
    }

    private companion object {
        /** A stand-in name, so this shared test never names a restricted permission. */
        const val READ_PERMISSION = "test.permission.READ"
    }
}
