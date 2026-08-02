package et.alpha.mezgeb.capture.notification

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether a posted notification is worth reading at all.
 *
 * The listener sees every notification on the device — chats, games, email. Almost
 * all of it is none of Mezgeb's business, and the cheapest way to honour that is to
 * decide on the package name before the text is ever touched.
 *
 * The default messaging app is the main source, because payment SMS is what this app
 * reads. Banking apps that post their own push notifications are the secondary
 * source, and that list is configurable rather than hard-coded: package names for
 * Ethiopian banking apps are not something to guess at, and a wrong guess is
 * invisible — messages simply never arrive, with nothing to show the user why.
 */
class NotificationSourceFilter(
    private val defaultSmsPackage: String?,
    private val watchedPackages: Set<String>,
) {

    fun allows(packageName: String): Boolean = when {
        packageName == defaultSmsPackage -> true
        packageName in watchedPackages -> true
        // Only when the system will not say which app handles SMS. Guessing while the
        // real answer is available would let a second messaging app in.
        defaultSmsPackage == null -> packageName in KNOWN_MESSAGING_PACKAGES
        else -> false
    }

    companion object {
        /**
         * Fallback for devices where the default SMS package cannot be read. These are
         * messaging apps, not banking apps — the SMS itself is what carries the payment.
         */
        val KNOWN_MESSAGING_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
            "com.miui.smsextra",
            "com.transsion.sms",
        )
    }
}

/**
 * Keeps a ready-to-use [NotificationSourceFilter] so that `onNotificationPosted` can
 * decide synchronously. Reading DataStore or the settings provider on the listener
 * callback would put I/O on a thread the system expects back immediately.
 */
@Singleton
class NotificationSourceFilterProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {

    private val watchedPackages: StateFlow<Set<String>> = settings.observeWatchedPackages()
        .stateIn(scope, SharingStarted.Eagerly, initialValue = emptySet())

    @Volatile
    private var defaultSmsPackage: String? = null

    /** Re-reads the default messaging app. Called when the listener (re)connects. */
    fun refresh() {
        defaultSmsPackage = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
    }

    fun current(): NotificationSourceFilter {
        if (defaultSmsPackage == null) refresh()
        return NotificationSourceFilter(defaultSmsPackage, watchedPackages.value)
    }
}
