package et.alpha.mezgeb.domain.reconcile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import et.alpha.mezgeb.R
import et.alpha.mezgeb.data.repo.ReconciliationRepository
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.util.MezgebDates
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nudges the shop to reconcile when a week has gone by without a closed period.
 *
 * Reconciliation is the habit the app is really selling, and habits need reminding
 * into existence. It is also the easiest feature to make obnoxious, so: once a day at
 * most, only when a week has actually passed, silent by importance, and switchable
 * off in one tap.
 */
@HiltWorker
class ReconcileReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val sessions: ReconciliationRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.reconcileRemindersEnabled()) return Result.success()

        val lastClosed = sessions.getMostRecentlyClosed()?.closedAt
        // Never reconciled: measure from first run, so a new install is not nagged on
        // day one and is not left alone forever either.
        val since = lastClosed ?: settings.firstRunAt() ?: return Result.success()

        if (clock.millis() - since < REMIND_AFTER_DAYS * MezgebDates.DAY_MILLIS) {
            return Result.success()
        }

        notifyIfPermitted()
        return Result.success()
    }

    private fun notifyIfPermitted() {
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS exists only from API 33. Below that the platform does not
        // know the permission, so checking it always answers "denied", and before this
        // guard the reminder never appeared on Android 8 to 12 at all (lint's
        // InlinedApi warning, read in ROADMAP R6). Those versions need no runtime
        // permission: the user's way to say no is the app's notification setting.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        // On API 33+ a refused permission means the notification simply does not
        // appear. That is the user's answer, not an error.
        if (!granted) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reconcile_reminder_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.reconcile_reminder_channel_description)
            },
        )

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.reconcile_reminder_title))
                .setContentText(context.getString(R.string.reconcile_reminder_body))
                .setSmallIcon(R.drawable.ic_stat_mezgeb)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val WORK_NAME = "reconcile_reminder"

        /** Per the session brief. */
        const val REMIND_AFTER_DAYS = 7L

        private const val CHANNEL_ID = "mezgeb_reconcile"
        private const val NOTIFICATION_ID = 8001
    }
}

/** Schedules the reminder. Idempotent — safe to call on every launch. */
@Singleton
class ReconcileReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ReconcileReminderWorker.WORK_NAME,
            // KEEP so that relaunching the app does not reset the interval and
            // postpone the reminder forever.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReconcileReminderWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
