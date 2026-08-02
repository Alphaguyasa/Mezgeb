package et.alpha.mezgeb.capture

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the whole review queue past the parser again, after a rule has been taught.
 *
 * In WorkManager rather than in the ViewModel because a shop that has just sideloaded
 * the app and imported eight months of inbox may have thousands of messages waiting.
 * That outlives the screen the rule was taught on, and the user should be free to
 * leave it while the backlog clears.
 *
 * No foreground notification: the play flavor declares no foreground service type, and
 * a job that only reads the local database and writes rows needs no user-visible
 * lifeline. Progress is reported through WorkManager and shown wherever someone is
 * watching.
 */
@HiltWorker
class ReprocessBacklogWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: CaptureCoordinator,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val summary = coordinator.reparseUnmatched { scanned, total ->
            setProgressAsync(workDataOf(KEY_SCANNED to scanned, KEY_TOTAL to total))
        }

        return Result.success(
            workDataOf(
                KEY_SCANNED to summary.scanned,
                KEY_TOTAL to summary.scanned,
                KEY_RECORDED to summary.recorded,
                KEY_DUPLICATES to summary.duplicates,
                KEY_IGNORED to summary.ignored,
            ),
        )
    }

    companion object {
        const val WORK_NAME = "reprocess_backlog"

        const val KEY_SCANNED = "scanned"
        const val KEY_TOTAL = "total"
        const val KEY_RECORDED = "recorded"
        const val KEY_DUPLICATES = "duplicates"
        const val KEY_IGNORED = "ignored"
    }
}

/** How far the backlog re-run has got, for whichever screen is watching. */
sealed interface ReprocessStatus {
    data object Idle : ReprocessStatus
    data class Running(val scanned: Int, val total: Int) : ReprocessStatus
    data class Finished(val recorded: Int, val scanned: Int) : ReprocessStatus
    data object Failed : ReprocessStatus
}

/**
 * Starting and watching the backlog re-run.
 *
 * A thin seam over WorkManager so ViewModels can be tested without one — WorkManager
 * needs initialising per process, which a unit test has no business doing.
 */
interface BacklogReprocessor {
    fun start()
    fun observe(): Flow<ReprocessStatus>
}

@Singleton
class WorkManagerBacklogReprocessor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : BacklogReprocessor {

    override fun start() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ReprocessBacklogWorker.WORK_NAME,
            // A second rule taught while the first pass is still running should queue
            // behind it, not cancel it: both passes have work to do.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReprocessBacklogWorker>().build(),
        )
    }

    override fun observe(): Flow<ReprocessStatus> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(ReprocessBacklogWorker.WORK_NAME)
        .map { infos -> infos.lastOrNull().toStatus() }
}

private fun WorkInfo?.toStatus(): ReprocessStatus = when (this?.state) {
    null -> ReprocessStatus.Idle
    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ReprocessStatus.Running(0, 0)

    WorkInfo.State.RUNNING -> ReprocessStatus.Running(
        scanned = progress.getInt(ReprocessBacklogWorker.KEY_SCANNED, 0),
        total = progress.getInt(ReprocessBacklogWorker.KEY_TOTAL, 0),
    )

    WorkInfo.State.SUCCEEDED -> ReprocessStatus.Finished(
        recorded = outputData.getInt(ReprocessBacklogWorker.KEY_RECORDED, 0),
        scanned = outputData.getInt(ReprocessBacklogWorker.KEY_SCANNED, 0),
    )

    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ReprocessStatus.Failed
}
