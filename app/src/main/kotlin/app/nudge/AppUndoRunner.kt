package app.nudge

import app.nudge.core.common.ApplicationScope
import app.nudge.core.domain.usecase.RestoreSnapshotUseCase
import app.nudge.core.domain.usecase.UndoRunner
import app.nudge.core.model.UndoSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Runs Undo on the application scope so it outlives the screen that offered it (03 §6). */
@Singleton
class AppUndoRunner @Inject constructor(
    private val restore: RestoreSnapshotUseCase,
    @param:ApplicationScope private val scope: CoroutineScope,
) : UndoRunner {
    override fun restore(snapshot: UndoSnapshot) {
        scope.launch { restore.invoke(snapshot) }
    }
}
