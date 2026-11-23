package et.alpha.mezgeb.domain.reconcile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.navigation.LaunchRequest
import et.alpha.mezgeb.util.MezgebDates
import et.alpha.mezgeb.util.MoneyFormatter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the evening closing check found for one day.
 *
 * Built from the same [PeriodReconciliation] the reconcile screen shows, through the
 * same [MissedMoney] fold as the weekly card on Today, so the notification, the card
 * and the screen behind both can never tell the shop three different stories.
 *
 * @param recordedInMinor money the ledger has coming in today: captured payments plus
 *   cash typed in by hand. Credit given is not money in, so it is left out.
 */
data class ClosingCheck(
    val recordedInMinor: Long,
    val missed: MissedMoney,
) {
    /** A clean day: the check says nothing at all rather than "all good". */
    val needsAttention: Boolean get() = !missed.isEmpty

    companion object {
        fun from(day: PeriodReconciliation): ClosingCheck = ClosingCheck(
            recordedInMinor = day.capturedInMinor + day.cashRecordedMinor,
            missed = MissedMoney.from(day.explanations),
        )
    }
}

/**
 * The nightly closing check: once each evening, if today's messages and balances point
 * to money the ledger does not explain, say how much in one notification.
 *
 * This is the weekly card brought down to the day the money moved, while the shop can
 * still remember who paid. It shares the restraint of [ReconcileReminderWorker]: it is
 * silent on a clean day, never speaks before evening, speaks at most once a day, is
 * low importance, and follows the same "reconcile reminders" switch in Settings. It has
 * its own notification channel, so the system settings can turn it off on its own.
 */
@HiltWorker
class ClosingCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val reconcile: ReconcilePeriodUseCase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val zone: ZoneId,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.reconcileRemindersEnabled()) return Result.success()

        val now = clock.millis()
        val local = Instant.ofEpochMilli(now).atZone(zone)
        // A daily job drifts, and Doze can hold it back for hours. Before evening the
        // day is not closed and "not explained today" would be premature, so an early
        // run does nothing and the next evening's run speaks instead.
        if (local.hour < EARLIEST_HOUR) return Result.success()

        val epochDay = local.toLocalDate().toEpochDay()
        if (settings.closingCheckDay() == epochDay) return Result.success()

        val today = MezgebDates.dayBounds(now, zone)
        val check = ClosingCheck.from(reconcile(today.first, today.last, openedAt = now))
        if (check.needsAttention) notifyIfPermitted(check)

        // Recorded on a clean day too: the day has been checked, and a second run the
        // same evening after a late payment is exactly the repeat this guard prevents.
        settings.markClosingCheckDone(epochDay)
        return Result.success()
    }

    private fun notifyIfPermitted(check: ClosingCheck) {
        // Same reasoning as ReconcileReminderWorker: the permission only exists from
        // API 33, and a refusal there is the user's answer, not an error to retry.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.closing_check_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.closing_check_channel_description)
            },
        )

        val (title, body) = texts(context, check)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                // The body is two sentences; collapsed, it would end mid-amount.
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_stat_mezgeb)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Immutable, for the reason given on [ReconcileReminderWorker]'s intent. */
    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        CONTENT_REQUEST_CODE,
        LaunchRequest.openReconcile(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val WORK_NAME = "closing_check"

        /** The hour the check is aimed at: when most small shops are closing up. */
        const val CLOSING_HOUR = 20

        /** Runs that land earlier than this (drift, Doze) are skipped, not shown. */
        const val EARLIEST_HOUR = 18

        private const val CHANNEL_ID = "mezgeb_closing_check"
        private const val NOTIFICATION_ID = 8002
        private const val CONTENT_REQUEST_CODE = 8002

        /**
         * Title and body. With an amount, both figures are named, because "200 birr"
         * means little without "of 3,000". Without one (unread messages that carried
         * no amount, or a possible double count), it says only that something is
         * there: inventing a figure would be the guess the parser never makes.
         */
        fun texts(context: Context, check: ClosingCheck): Pair<String, String> {
            val missing = check.missed.possiblyMissingMinor
            return if (missing > 0) {
                val amount = MoneyFormatter.format(missing)
                context.getString(R.string.closing_check_title_amount, amount) to
                    context.getString(
                        R.string.closing_check_body_amount,
                        MoneyFormatter.format(check.recordedInMinor),
                        amount,
                    )
            } else {
                context.getString(R.string.closing_check_title_items) to
                    context.getString(R.string.closing_check_body_items)
            }
        }

        /** How long from [now] until the next [CLOSING_HOUR] o'clock in [zone]. */
        fun delayUntilClosing(now: Instant, zone: ZoneId): Duration {
            val local: ZonedDateTime = now.atZone(zone)
            var next = local.toLocalDate().atTime(CLOSING_HOUR, 0).atZone(zone)
            if (!next.isAfter(local)) next = next.plusDays(1)
            return Duration.between(local, next)
        }
    }
}

/** Schedules the closing check. Idempotent: safe to call on every launch. */
@Singleton
class ClosingCheckScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    fun schedule() {
        val delay = ClosingCheckWorker.delayUntilClosing(clock.instant(), zone)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ClosingCheckWorker.WORK_NAME,
            // KEEP: a relaunch must not move the evening slot, and the initial delay
            // only matters the first time.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ClosingCheckWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
                .build(),
        )
    }
}
