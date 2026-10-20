package et.alpha.mezgeb.ui.onboarding

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.capture.sms.SmsImportStatus
import et.alpha.mezgeb.capture.sms.SmsImporter
import et.alpha.mezgeb.capture.vendor.BatteryOptimizationHelper
import et.alpha.mezgeb.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingUiState(
    val isLoading: Boolean = true,
    val hasNotificationAccess: Boolean = false,
    /** True only in the sideloaded build, which is the only one that can backfill. */
    val canImportPastMessages: Boolean = false,
    val importStatus: SmsImportStatus = SmsImportStatus.Unavailable,
    /** True when this device has a vendor screen worth visiting and has not been. */
    val showBatteryHint: Boolean = false,
    /**
     * True after the user refused the permission the import needs, until they try
     * again or grant it in settings. Without it a refusal looks like a button that
     * does nothing, and after "don't ask again" that is literally what it becomes.
     */
    val importPermissionRefused: Boolean = false,
)

sealed interface OnboardingEvent {
    /** Re-checks notification access. Sent when the screen comes back to the front. */
    data object Refresh : OnboardingEvent

    data object StartImport : OnboardingEvent

    /** The system dialog came back with the import's permission refused. */
    data object ImportPermissionRefused : OnboardingEvent

    /** The import's permission is now held, granted in settings after a refusal. */
    data object ImportPermissionGranted : OnboardingEvent

    /** The user has been sent to the vendor's battery screen; do not ask again. */
    data object BatterySettingsOpened : OnboardingEvent

    data object Finish : OnboardingEvent
}

/**
 * State for the screen that explains what Mezgeb reads and asks for access.
 *
 * Notification access cannot be observed — the system has no callback for it being
 * granted or revoked — so it is re-read on [OnboardingEvent.Refresh], which the
 * screen sends when it resumes. That is exactly when it changes: the user has just
 * come back from settings.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val access: CaptureAccess,
    private val battery: BatteryOptimizationHelper,
    private val importer: SmsImporter,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val refreshes = MutableStateFlow(0)

    private val importPermissionRefused = MutableStateFlow(false)

    /** Resolved once: the answer depends on the firmware, which does not change. */
    private val hasVendorBatteryScreen: Boolean = battery.hasVendorScreen()

    val state: StateFlow<OnboardingUiState> = combine(
        importer.observeStatus(),
        settings.observeBatteryHintShown(),
        refreshes,
        importPermissionRefused,
    ) { importStatus, batteryHintShown, _, permissionRefused ->
        OnboardingUiState(
            isLoading = false,
            hasNotificationAccess = access.isNotificationAccessGranted(),
            canImportPastMessages = importer.isSupported,
            importStatus = importStatus,
            showBatteryHint = hasVendorBatteryScreen && !batteryHintShown,
            importPermissionRefused = permissionRefused,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = OnboardingUiState(),
    )

    fun onEvent(event: OnboardingEvent) {
        when (event) {
            OnboardingEvent.Refresh -> refreshes.update { it + 1 }
            OnboardingEvent.StartImport -> {
                importPermissionRefused.value = false
                importer.start()
            }
            OnboardingEvent.ImportPermissionRefused -> importPermissionRefused.value = true
            OnboardingEvent.ImportPermissionGranted -> importPermissionRefused.value = false
            OnboardingEvent.BatterySettingsOpened -> viewModelScope.launch {
                settings.setBatteryHintShown(true)
            }
            OnboardingEvent.Finish -> viewModelScope.launch {
                settings.setOnboardingCompleted(true)
            }
        }
    }

    /**
     * The intents and permission names the screen needs to hand to the system.
     *
     * Exposed as functions rather than baked into the state so the UI never builds an
     * `Intent` itself, and — for [importPermissions] — so no shared UI code has to
     * name a permission that only one flavor has.
     */
    fun notificationSettingsIntent(): Intent = access.settingsIntent()

    fun batterySettingsIntent(): Intent = battery.settingsIntent()

    fun appSettingsIntent(): Intent = access.appSettingsIntent()

    fun importPermissions(): List<String> = importer.requiredPermissions()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
