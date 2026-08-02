package et.alpha.mezgeb.capture.sms

import android.Manifest
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the inbox backfill as a WorkManager job. `direct` flavor only.
 *
 * WorkManager rather than a coroutine on an application scope: an import of several
 * thousand messages outlives the screen that started it, needs to survive the process
 * being killed halfway, and needs a foreground notification to be allowed to keep
 * running at all.
 */
@Singleton
class DirectSmsImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SmsImporter {

    override val isSupported: Boolean = true

    /**
     * The one place in the app that names the restricted permission from Kotlin. It
     * is handed to the UI as an opaque string list so the shared onboarding screen can
     * request it without either flavor's UI code knowing what it is.
     */
    override fun requiredPermissions(): List<String> = listOf(Manifest.permission.READ_SMS)

    override fun start() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SmsImportWorker.WORK_NAME,
            // KEEP, not REPLACE: a second tap while an import is running should join
            // the one in flight, not restart it from the cursor and redo the work.
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SmsImportWorker>().build(),
        )
    }

    override fun observeStatus(): Flow<SmsImportStatus> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(SmsImportWorker.WORK_NAME)
        .map { infos -> infos.lastOrNull().toStatus() }
}

private fun WorkInfo?.toStatus(): SmsImportStatus = when (this?.state) {
    null -> SmsImportStatus.Idle
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SmsImportStatus.Running(0, 0, 0)

    WorkInfo.State.RUNNING -> SmsImportStatus.Running(
        imported = progress.getInt(SmsImportWorker.KEY_IMPORTED, 0),
        scanned = progress.getInt(SmsImportWorker.KEY_SCANNED, 0),
        total = progress.getInt(SmsImportWorker.KEY_TOTAL, 0),
    )

    WorkInfo.State.SUCCEEDED -> SmsImportStatus.Finished(
        imported = outputData.getInt(SmsImportWorker.KEY_IMPORTED, 0),
        duplicates = outputData.getInt(SmsImportWorker.KEY_DUPLICATES, 0),
        queued = outputData.getInt(SmsImportWorker.KEY_QUEUED, 0),
    )

    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> SmsImportStatus.Failed
}
