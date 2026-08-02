package et.alpha.mezgeb.capture.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.capture.CapturedMessage
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.di.ApplicationScope
import et.alpha.mezgeb.domain.model.CaptureSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads payment notifications. The one capture path that both flavors share.
 *
 * Why a notification listener and not an SMS receiver: Google Play treats SMS read
 * permissions as restricted, and finance tracking is not an approved exception, so
 * an app that declares them is removed. The notification carries the same text, the
 * access is explicit and revocable, and it is not a Play-restricted permission. See
 * 03-ARCHITECTURE.md section 1.
 *
 * The callback contract is the important part of this class. `onNotificationPosted`
 * runs on the main thread of a system-bound service: it decides cheaply whether the
 * notification is interesting, copies the two strings it needs out of the extras,
 * and hands off. Nothing is parsed and no database is touched before it returns.
 */
@AndroidEntryPoint
class MezgebNotificationListener : NotificationListenerService() {

    @Inject lateinit var coordinator: CaptureCoordinator

    @Inject lateinit var filters: NotificationSourceFilterProvider

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var access: CaptureAccess

    /**
     * Application-scoped rather than service-scoped on purpose. The system unbinds
     * this service aggressively; work already handed off must finish and write its
     * row rather than be cancelled halfway through inserting a payment.
     */
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onListenerConnected() {
        super.onListenerConnected()
        filters.refresh()
        scope.launch { settings.recordAccessLost(null) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Either access was revoked or the system killed the binding. Asking to be
        // rebound is what makes the second case recoverable without a reboot; if it
        // was the first case the request is refused, which is correct.
        access.requestRebind()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (!filters.current().allows(sbn.packageName)) return

        // "3 new messages" summaries carry no body worth parsing, and the individual
        // notifications they group are posted separately anyway.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        // BigTextStyle is the common case for anything longer than a line, and a bank
        // message always is. EXTRA_TEXT holds the collapsed, ellipsised version — using
        // it would silently truncate the fields at the end of the message, which for
        // CBE is the balance and the transaction reference.
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim()

        if (sender.isNullOrEmpty() || body.isNullOrEmpty()) return

        val message = CapturedMessage(
            sender = sender,
            body = body,
            receivedAt = sbn.postTime,
            source = CaptureSource.NOTIFICATION,
        )
        scope.launch { coordinator.capture(message) }
    }
}
