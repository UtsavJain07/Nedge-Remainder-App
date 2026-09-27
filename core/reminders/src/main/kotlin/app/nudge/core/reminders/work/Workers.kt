package app.nudge.core.reminders.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.nudge.core.common.Clock
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.reminders.engine.ReminderDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Safety net every 15 min: dispatch anything overdue and re-arm the alarm (07 §6, NFR-02). */
@HiltWorker
class ReconcilerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dispatcher: ReminderDispatcher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        dispatcher.dispatchDue()
        dispatcher.armNextAlarm()
        Result.success()
    } catch (e: Exception) {
        Timber.e(e, "Reconciler failed")
        Result.retry()
    }

    companion object {
        const val NAME = "reminder-reconciler"
    }
}

/** Hard-deletes rows soft-deleted more than 30 days ago (06 §11). */
@HiltWorker
class PurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tasks: TaskRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val purged = tasks.purgeDeleted(clock.now().minus(RETENTION))
        Timber.d("Purged %d rows", purged)
        return Result.success()
    }

    companion object {
        const val NAME = "purge-deleted"
        val RETENTION: Duration = Duration.ofDays(30)
    }
}

/** Enqueues the periodic workers (called from Application.onCreate). */
object ReminderWork {
    fun enqueue(workManager: WorkManager) {
        workManager.enqueueUniquePeriodicWork(
            ReconcilerWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReconcilerWorker>(15, TimeUnit.MINUTES).build(),
        )
        workManager.enqueueUniquePeriodicWork(
            PurgeWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PurgeWorker>(24, TimeUnit.HOURS).build(),
        )
    }
}
