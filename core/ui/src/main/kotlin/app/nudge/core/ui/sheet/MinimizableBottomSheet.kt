package app.nudge.core.ui.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.ui.R
import kotlinx.coroutines.launch

/** Whether a [MinimizableBottomSheet] is fully open or minimized to its peek bar. */
@Stable
class MinimizableSheetController internal constructor() {
    var minimized by mutableStateOf(false)
        internal set

    internal var closeAction: (() -> Unit)? = null

    fun expand() {
        minimized = false
    }

    fun minimize() {
        minimized = true
    }

    /** Closes the sheet for good (after saving or discarding). */
    fun close() {
        closeAction?.invoke()
    }
}

@Composable
fun rememberMinimizableSheetController(): MinimizableSheetController = remember { MinimizableSheetController() }

private val PeekHeight = 88.dp
private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * A bottom sheet that **minimizes instead of closing** (v1.1 feedback). Dragging it down, flinging it,
 * tapping outside or pressing Back slides it down to a compact bar that keeps everything typed so far;
 * tapping the bar or dragging it up brings it back. From the bar, the same gestures (or its × button)
 * close the sheet once [onCloseRequest] agrees — return false (after showing your own "Discard?" dialog)
 * to keep it open.
 *
 * The content stays mounted the whole time, so its state is never lost, and the sheet height never
 * depends on which mode it is in. Drawn inside the calling screen (not a separate window), on top of it.
 */
@Composable
fun MinimizableBottomSheet(
    controller: MinimizableSheetController,
    peekTitle: String,
    onCloseRequest: () -> Boolean,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val canClose by rememberUpdatedState(onCloseRequest)
    val dismissed by rememberUpdatedState(onDismissed)

    var sheetHeight by remember { mutableFloatStateOf(0f) }
    // Distance the sheet is pushed down from fully open, in px. NaN until the first measure (entrance).
    val offset = remember { Animatable(Float.NaN) }
    var closing by remember { mutableStateOf(false) }
    val peekPx = with(density) { PeekHeight.toPx() }
    val minimizedOffset = (sheetHeight - peekPx).coerceAtLeast(0f)
    val settleSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 500f)

    fun targetOffset(): Float = if (controller.minimized) minimizedOffset else 0f

    fun animateClose() {
        if (closing) return
        closing = true
        scope.launch {
            offset.animateTo(sheetHeight + peekPx, tween(220))
            dismissed()
        }
    }
    controller.closeAction = ::animateClose

    fun requestCloseOrStay() {
        if (canClose()) {
            animateClose()
        } else {
            scope.launch { offset.animateTo(minimizedOffset, settleSpec) }
        }
    }

    /** One place for every "go down" action: open → minimize; minimized → close. */
    fun stepDown() {
        if (controller.minimized) requestCloseOrStay() else controller.minimize()
    }

    fun settle(velocity: Float) {
        val threshold = with(density) { 56.dp.toPx() }
        val fast = with(density) { 1200.dp.toPx() }
        val now = offset.value
        if (controller.minimized) {
            when {
                velocity < -fast || now < minimizedOffset - threshold -> controller.expand()
                velocity > fast || now > minimizedOffset + threshold -> requestCloseOrStay()
                else -> scope.launch { offset.animateTo(minimizedOffset, settleSpec) }
            }
        } else {
            if (velocity > fast || now > sheetHeight * 0.25f) {
                controller.minimize()
            } else {
                scope.launch { offset.animateTo(0f, settleSpec) }
            }
        }
    }

    // Mode or height changed (e.g. keyboard shown/hidden) → glide to the matching position.
    LaunchedEffect(controller.minimized, sheetHeight) {
        if (sheetHeight <= 0f || closing) return@LaunchedEffect
        if (offset.value.isNaN()) offset.snapTo(sheetHeight)
        offset.animateTo(targetOffset(), settleSpec)
    }
    LaunchedEffect(controller.minimized) {
        if (controller.minimized) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }

    BackHandler(enabled = !closing) { stepDown() }

    val dragState = rememberDraggableState { delta ->
        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, sheetHeight)) }
    }
    // Content scrolling hands over to the sheet at its edges (like Material's modal sheet).
    val nested = remember(sheetHeight, controller.minimized) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val y = available.y
                if (y >= 0f || offset.value <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val consumed = maxOf(y, -offset.value)
                scope.launch { offset.snapTo(offset.value + consumed) }
                return Offset(0f, consumed)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val y = available.y
                if (y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                scope.launch { offset.snapTo((offset.value + y).coerceIn(0f, sheetHeight)) }
                return Offset(0f, y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset.value.isNaN() || kotlin.math.abs(offset.value - targetOffset()) < 1f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }

    val scrimAlpha by animateFloatAsState(
        when {
            closing -> 0f
            controller.minimized -> 0.12f
            else -> 0.32f
        },
        tween(200),
        label = "scrim",
    )
    val scrimLabel = stringResource(if (controller.minimized) R.string.sheet_close else R.string.sheet_minimize)
    val sheetPaneTitle = stringResource(R.string.sheet_pane)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSheetHeight = maxHeight - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 24.dp
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = scrimLabel,
                    onClick = { if (!closing) stepDown() },
                ),
        )
        Surface(
            shape = SheetShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 8.dp,
            modifier = modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                .graphicsLayer { translationY = if (offset.value.isNaN()) size.height else offset.value }
                .semantics { paneTitle = sheetPaneTitle }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = !closing,
                    onDragStopped = { velocity -> settle(velocity) },
                )
                .nestedScroll(nested),
        ) {
            Box(Modifier.navigationBarsPadding().imePadding()) {
                Column {
                    DragHandle()
                    content()
                }
                AnimatedVisibility(
                    visible = controller.minimized,
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(120)),
                ) {
                    PeekBar(title = peekTitle, onExpand = controller::expand, onClose = { requestCloseOrStay() })
                }
            }
        }
    }
}

@Composable
private fun DragHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.m), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}

/** Shown over the top of the sheet while minimized: what's being edited, tap to continue, × to close. */
@Composable
private fun PeekBar(title: String, onExpand: () -> Unit, onClose: () -> Unit) {
    val expandLabel = stringResource(R.string.sheet_expand)
    Column(
        Modifier
            .fillMaxWidth()
            .height(PeekHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("sheet_peek")
            .clickable(role = Role.Button, onClick = onExpand)
            .semantics { onClick(label = expandLabel) { onExpand(); true } },
    ) {
        DragHandle()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.sheetPadding, end = Spacing.s),
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.sheet_tap_to_continue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.testTag("sheet_close")) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.sheet_close))
            }
        }
    }
}
