package app.nudge.core.ui.dnd

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.component.HapticEvent
import app.nudge.core.designsystem.component.rememberNudgeHaptics
import app.nudge.core.model.MoveOperation

@Composable
fun rememberDragDropState(listState: LazyListState, onMove: (MoveOperation) -> Unit): DragDropState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val haptics = rememberNudgeHaptics()
    val currentOnMove by rememberUpdatedState(onMove)
    val state = remember(listState, scope, density) {
        DragDropState(
            layout = LazyListDndLayout(listState),
            scope = scope,
            density = density,
            onMove = { currentOnMove(it) },
            feedback = object : DndFeedback {
                override fun dragStart() = haptics.perform(HapticEvent.DRAG_START)
                override fun slotChanged() = haptics.perform(HapticEvent.SLOT_CHANGE)
                override fun nestArmed() = haptics.perform(HapticEvent.NEST_ARMED)
                override fun rejected() = haptics.perform(HapticEvent.REJECT)
            },
        )
    }
    // Every layout pass: keep the dragged row under the finger (08 §3.3).
    LaunchedEffect(state, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key to it.offset } }
            .collect { state.onLayout() }
    }
    return state
}

/**
 * DD-02: long-press (system timeout) then move = drag; released with < 8 dp of movement = context menu.
 * The row's tap / checkbox keep working because this only consumes after the long-press timeout.
 */
fun Modifier.dragToReorder(
    state: DragDropState,
    key: String,
    enabled: Boolean,
    rows: () -> List<DndRow>,
    onLongPressMenu: () -> Unit,
    onDisabledLongPress: () -> Unit = onLongPressMenu,
): Modifier = pointerInput(key, enabled) {
    val slop = 8.dp.toPx()
    var total = Offset.Zero
    detectDragGesturesAfterLongPress(
        onDragStart = {
            total = Offset.Zero
            if (enabled) state.onDragStart(key, rows())
        },
        onDrag = { change, amount ->
            change.consume()
            total += amount
            if (enabled) {
                state.onDrag(amount)
                if (amount.x != 0f) state.reevaluate()
            }
        },
        onDragEnd = {
            if (total.getDistance() < slop) {
                if (enabled) state.onDragCancel()
                if (enabled) onLongPressMenu() else onDisabledLongPress()
            } else if (enabled) {
                state.onDragEnd()
            }
        },
        onDragCancel = { if (enabled) state.onDragCancel() },
    )
}
