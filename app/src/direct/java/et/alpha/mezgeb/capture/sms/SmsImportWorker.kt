package et.alpha.mezgeb.capture.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import et.alpha.mezgeb.R
import et.alpha.mezgeb.capture.CaptureCoordinator
import et.alpha.mezgeb.capture.CaptureOutcome
import et.alpha.mezgeb.capture.CapturedMessage
import et.alpha.mezgeb.data.settings.SettingsRepository
import et.alpha.mezgeb.domain.model.CaptureSource
import java.time.Clock

/**
 * Backfills the inbox into the ledger. `direct` flavor only.
 *
 * Every message goes through [CaptureCoordinator], the same funnel the notification
 * listener uses, so a payment already captured live is rejected by the dedup index
 * rather than recorded twice.
 *
 * A phone's inbox is mostly private correspondence, and the app has no business
 * storing any of it. The funnel refuses anything from outside the financial sender
 * allowlist before it writes a row, so a message from a friend is read out of the
 * cursor, declined, and never reaches the database. That rule lives in the funnel
 * rather than here, so the notification listener obeys it too — it did not, until a
 * plain SMS on a real phone showed that it did not.
 */
@HiltWorker
class SmsImportWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val reader: InboxSource,
    private val coordinator: CaptureCoordinator,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!hasInboxPermission()) return Result.failure()

        val since = settings.smsImportSince()
        val total = reader.count(since)
        notifyProgress(scanned = 0, total = total)

        var scanned = 0
        var offset = 0
        var imported = 0
        var duplicates = 0
        var queued = 0
        var skippedCredentials = 0
        var newestDate = since

        while (true) {
            val page = reader.page(since = since, offset = offset, limit = PAGE_SIZE)
            if (page.isEmpty()) break

            for (row in page) {
                scanned++
                offset++
                if (row.date > newestDate) newestDate = row.date

                val outcome = coordinator.capture(
                    CapturedMessage(
                        sender = row.address,
                        body = row.body,
                        receivedAt = row.date,
                        source = CaptureSource.SMS_IMPORT,
                    ),
                )
                when (outcome) {
                    is CaptureOutcome.Recorded -> imported++
                    is CaptureOutcome.Duplicate -> duplicates++
                    is CaptureOutcome.Queued -> queued++
                    is CaptureOutcome.Ignored -> Unit
                    // Counted, never stored. The shop is told how many were passed
                    // over so the import's arithmetic still adds up for them.
                    CaptureOutcome.DeclinedAsCredential -> skippedCredentials++
                    // A phone's inbox is mostly private correspondence. None of it is
                    // read past the sender, and none of it is written down.
                    CaptureOutcome.DeclinedAsNotFinancial -> Unit
                }
            }

            setProgress(progressData(imported, scanned, total))
            notifyProgress(scanned = scanned, total = total)

            if (page.size < PAGE_SIZE) break
        }

        // Only after a clean pass. A run that dies halfway leaves the cursor where it
        // was, so the next attempt re-reads the same rows — which the dedup index
        // makes cheap and harmless, and which is far better than skipping a payment.
        settings.setSmsImportSince(newestDate)
        settings.recordSmsImport(clock.millis())

        return Result.success(
            workDataOf(
                KEY_IMPORTED to imported,
                KEY_SCANNED to scanned,
                KEY_TOTAL to total,
                KEY_DUPLICATES to duplicates,
                KEY_QUEUED to queued,
                KEY_SKIPPED_CREDENTIALS to skippedCredentials,
            ),
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(scanned = 0, total = 0)

    private fun hasInboxPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun notifyProgress(scanned: Int, total: Int) {
        // On API 31+ a foreground service cannot always be started from the
        // background. The import is started from a screen the user is looking at, so
        // it normally can — but losing the progress notification must not lose the
        // import, so the failure is swallowed and the work carries on silently.
        runCatching { setForeground(foregroundInfo(scanned, total)) }
    }

    private fun foregroundInfo(scanned: Int, total: Int): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sms_import_notification_title))
            .setContentText(
                context.getString(R.string.sms_import_notification_progress, scanned, total),
            )
            .setSmallIcon(R.drawable.ic_stat_mezgeb)
            .setOngoing(true)
            .setProgress(total, scanned, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sms_import_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.sms_import_channel_description)
            setShowBadge(false)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun progressData(imported: Int, scanned: Int, total: Int): Data = workDataOf(
        KEY_IMPORTED to imported,
        KEY_SCANNED to scanned,
        KEY_TOTAL to total,
    )

    companion object {
        const val WORK_NAME = "sms_import"

        const val KEY_IMPORTED = "imported"
        const val KEY_SCANNED = "scanned"
        const val KEY_TOTAL = "total"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_QUEUED = "queued"
        const val KEY_SKIPPED_CREDENTIALS = "skipped_credentials"

        /** Per 03-ARCHITECTURE.md section 6. */
        private const val PAGE_SIZE = 500

        private const val CHANNEL_ID = "mezgeb_import"
        private const val NOTIFICATION_ID = 4001
    }
}
