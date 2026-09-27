package app.nudge

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import app.nudge.core.common.ApplicationScope
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.reminders.work.ReminderWork
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Seeds the default list, reschedules reminders on every cold start (cheap; also covers an Auto
 * Backup restore, 06 §10) and enqueues the reconciler + purge workers (07 §6).
 */
@HiltAndroidApp
class NudgeApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var lists: ListRepository

    @Inject lateinit var scheduler: ReminderScheduler

    @Inject @field:ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build())
        }
        appScope.launch {
            lists.ensureDefaultList()
            scheduler.rescheduleAll()
        }
        ReminderWork.enqueue(WorkManager.getInstance(this))
    }
}
