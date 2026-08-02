package et.alpha.mezgeb.capture.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.mezgeb.capture.notification.CaptureAccess
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * Re-establishes capture after a reboot or an app update.
 *
 * A notification listener does not automatically come back to life after either
 * event on every device, and when it does not, the app looks entirely normal while
 * capturing nothing. Two things happen here: the binding is requested again, and if
 * access has been revoked outright the moment is recorded so the UI can say so
 * instead of showing an empty ledger and letting the shop draw its own conclusion.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var access: CaptureAccess

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var clock: Clock

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        // The DataStore write is asynchronous, so the broadcast has to be held open
        // until it finishes or the process may be killed first.
        val pending = goAsync()
        scope.launch {
            try {
                if (access.isNotificationAccessGranted()) {
                    access.requestRebind()
                    settings.recordAccessLost(null)
                } else {
                    settings.recordAccessLost(clock.millis())
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
