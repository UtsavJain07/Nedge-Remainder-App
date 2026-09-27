package app.nudge.core.database.di

import android.content.Context
import androidx.room.Room
import app.nudge.core.database.NudgeDatabase
import app.nudge.core.database.dao.TaskDao
import app.nudge.core.database.dao.TaskListDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): NudgeDatabase =
        Room.databaseBuilder(context, NudgeDatabase::class.java, NudgeDatabase.NAME)
            // 06 §9: never destructive in release; downgrades only happen on debug installs.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun taskDao(db: NudgeDatabase): TaskDao = db.taskDao()

    @Provides
    fun taskListDao(db: NudgeDatabase): TaskListDao = db.taskListDao()
}
