package et.alpha.mezgeb.capture.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification access — is it granted, how does the user grant it, and how does the
 * app ask to be reconnected.
 *
 * An interface because everything above it (capture health, onboarding) is worth
 * testing without a real system settings provider.
 */
interface CaptureAccess {

    /** True when the user has enabled Mezgeb in system notification-access settings. */
    fun isNotificationAccessGranted(): Boolean

    /** The system screen where the user grants or revokes it. */
    fun settingsIntent(): Intent

    /**
     * Asks the system to bind the listener again. Safe to call when already bound.
     * Needed after a reboot and after the service has been killed.
     */
    fun requestRebind()
}

@Singleton
class AndroidCaptureAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CaptureAccess {

    private val listenerComponent: ComponentName
        get() = ComponentName(context, MezgebNotificationListener::class.java)

    override fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    override fun settingsIntent(): Intent {
        // From API 30 the system can open this app's own row directly. On older
        // versions the user lands on a list and has to find Mezgeb in it, which the
        // onboarding copy says out loud rather than pretending otherwise.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                listenerComponent.flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun requestRebind() {
        // Throws if the component is not an enabled listener, which is a normal state
        // (access revoked, or never granted) rather than an error worth propagating.
        runCatching { NotificationListenerService.requestRebind(listenerComponent) }
    }
}
