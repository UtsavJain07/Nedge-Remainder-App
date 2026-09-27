package app.nudge.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nudge.core.common.DispatcherProvider
import app.nudge.core.data.repository.ListRepositoryImpl
import app.nudge.core.data.repository.TaskRepositoryImpl
import app.nudge.core.database.NudgeDatabase
import app.nudge.core.database.mapper.toEntity
import app.nudge.core.datastore.SettingsDataSource
import app.nudge.core.model.Task
import app.nudge.core.model.TaskList
import app.nudge.core.testing.SequentialIdGenerator
import app.nudge.core.testing.TestClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

object TestDispatchers : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.Unconfined
    override val default: CoroutineDispatcher = Dispatchers.Unconfined
    override val main: CoroutineDispatcher = Dispatchers.Unconfined
}

/** In-memory Room + temp-file DataStore + real repositories (11 §1 data layer). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class DataTestBase {
    @get:Rule
    val tmp = TemporaryFolder()

    val clock = TestClock()
    lateinit var db: NudgeDatabase
    lateinit var dataStore: DataStore<Preferences>
    lateinit var settings: SettingsDataSource
    lateinit var tasks: TaskRepositoryImpl
    lateinit var lists: ListRepositoryImpl
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Before
    fun setUpData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NudgeDatabase::class.java).allowMainThreadQueries().build()
        dataStore = newDataStore("settings")
        settings = SettingsDataSource(dataStore)
        tasks = TaskRepositoryImpl(db, db.taskDao(), settings, clock, SequentialIdGenerator("task"), TestDispatchers)
        lists = ListRepositoryImpl(
            db, db.taskListDao(), db.taskDao(), clock, SequentialIdGenerator("list"), TestDispatchers,
        )
    }

    @After
    fun tearDownData() {
        db.close()
        dataStoreScope.cancel()
    }

    fun newDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = dataStoreScope) { File(tmp.root, "$name.preferences_pb") }

    fun seedLists(vararg rows: TaskList) = runBlocking { db.taskListDao().upsert(rows.map { it.toEntity() }) }

    /** Upserts rows directly, parents first (FK). */
    fun seedTasks(vararg rows: Task) = runBlocking {
        db.taskDao().upsert(rows.sortedBy { it.parentId != null }.map { it.toEntity() })
    }
}
