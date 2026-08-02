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
import et.alpha.mezgeb.parser.rule.SenderAllowlist
import java.time.Clock

/**
 * Backfills the inbox into the ledger. `direct` flavor only.
 *
 * Every message goes through [CaptureCoordinator], the same funnel the notification
 * listener uses, so a payment already captured live is rejected by the dedup index
 * rather than recorded twice.
 *
 * Note where the sender allowlist is applied: **before** the coordinator, not inside
 * it. A phone's inbox is mostly personal correspondence, and the app has no business
 * storing any of it. Filtering here means a message from a friend is read from the
 * cursor, found not to be from a financial sender, and dropped — it never reaches the
 * database at all.
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

    private val allowlist = SenderAllowlist.DEFAULT

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
        var newestDate = since

        while (true) {
            val page = reader.page(since = since, offset = offset, limit = PAGE_SIZE)
            if (page.isEmpty()) break

            for (row in page) {
                scanned++
                offset++
                if (row.date > newestDate) newestDate = row.date
                if (!allowlist.allows(row.address)) continue

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

        /** Per 03-ARCHITECTURE.md section 6. */
        private const val PAGE_SIZE = 500

        private const val CHANNEL_ID = "mezgeb_import"
        private const val NOTIFICATION_ID = 4001
    }
}
