package et.alpha.mezgeb.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.sms.SmsImportStatus
import et.alpha.mezgeb.ui.onboarding.OnboardingScreen
import et.alpha.mezgeb.ui.onboarding.OnboardingUiState
import et.alpha.mezgeb.ui.settings.SettingsScreen
import et.alpha.mezgeb.ui.settings.SettingsUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings and onboarding. The rules are in [AccessibilityChecks].
 *
 * Neither shows money. What they have instead are switches and setup buttons, the
 * controls a first-time user meets before anything else, so a nameless one here is
 * the first thing TalkBack gets wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class SettingsOnboardingAccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val checks = AccessibilityChecks(compose)
    private val context = checks.context

    @Test
    fun `settings names and sizes every control`() {
        showSettings()

        checks.assertControlsAreNamedAndLargeEnough()
        checks.assertSpoken(context.getString(R.string.cd_navigate_back))
    }

    @Test
    fun `each switch is read with the setting it changes`() {
        showSettings()

        // Before the rows became the control, each Switch was its own nameless node,
        // read as "switch, on" with no word of what it turned on.
        assertSwitchNamed(R.string.settings_lock)
        assertSwitchNamed(R.string.settings_biometric)
        assertSwitchNamed(R.string.settings_demo)
    }

    /** ROADMAP R39: a restore in demo mode would be deleted with the demo file. */
    @Test
    fun `in demo mode restore is disabled and the reason is shown`() {
        showSettings(SettingsUiState(isDemoMode = true))

        compose.onNodeWithText(context.getString(R.string.settings_restore_action))
            .assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_restore_demo_blocked))
            .assertExists()
        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `outside demo mode restore is enabled and no reason is shown`() {
        showSettings(SettingsUiState())

        compose.onNodeWithText(context.getString(R.string.settings_restore_action))
            .assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_restore_demo_blocked))
            .assertDoesNotExist()
    }

    @Test
    fun `onboarding without access names and sizes every control`() {
        showOnboarding(
            OnboardingUiState(
                isLoading = false,
                hasNotificationAccess = false,
                canImportPastMessages = true,
                importStatus = SmsImportStatus.Idle,
                showBatteryHint = true,
                // Shows the "open app settings" button too.
                importPermissionRefused = true,
            ),
        )

        checks.assertControlsAreNamedAndLargeEnough()
    }

    @Test
    fun `onboarding with access names and sizes every control`() {
        showOnboarding(
            OnboardingUiState(
                isLoading = false,
                hasNotificationAccess = true,
                canImportPastMessages = true,
                importStatus = SmsImportStatus.Finished(
                    imported = 12,
                    duplicates = 3,
                    queued = 1,
                    skippedCredentials = 2,
                ),
            ),
        )

        checks.assertControlsAreNamedAndLargeEnough()
    }

    private fun assertSwitchNamed(@StringRes labelRes: Int) {
        val label = context.getString(labelRes)
        val found = compose.onAllNodes(hasText(label) and isToggleable()).fetchSemanticsNodes()
        assertTrue("no switch is read as \"$label\"", found.isNotEmpty())
    }

    // Lock on, so the biometric switch and the timeout chips show too.
    private fun showSettings(
        state: SettingsUiState = SettingsUiState(isLockEnabled = true, isBiometricEnabled = true),
    ) {
        checks.show {
            SettingsScreen(
                state = state,
                onEvent = {},
                onBack = {},
                onBackupConfirmed = {},
                onRestoreRequested = {},
                onRestoreConfirmed = { _, _ -> },
                onLanguageChosen = {},
            )
        }
    }

    private fun showOnboarding(state: OnboardingUiState) {
        checks.show {
            OnboardingScreen(
                state = state,
                onEvent = {},
                onOpenNotificationSettings = {},
                onOpenBatterySettings = {},
                onOpenAppSettings = {},
                onStartImport = {},
                onFinish = {},
            )
        }
    }
}
