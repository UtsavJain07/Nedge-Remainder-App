package app.nudge.core.ui.dnd

import app.nudge.core.model.MoveOperation
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 08 §7: T4 depth resolution, T5 drop operations, T6 no-op drops, DD-07 / DD-09 helpers. */
class DndLogicTest {
    private val t = 48f

    private fun top(key: String, hasChildren: Boolean = false, expanded: Boolean = true) =
        DndRow(key, 0, null, hasChildren, expanded)

    private fun sub(key: String, parent: String, completed: Boolean = false) =
        DndRow(key, 1, parent, false, true, completed)

    // --- T4: resolveDepth ---
    @Test fun top_of_list_is_always_depth_0() {
        val rows = listOf(top("a"), top("b"))
        assertThat(DndLogic.resolveDepth(rows, 0, top("x"), 1, 100f, t)).isEqualTo(0)
    }

    @Test fun under_a_top_level_row_indents_when_dragged_right() {
        val rows = listOf(top("a"), top("b"))
        assertThat(DndLogic.resolveDepth(rows, 1, top("x"), 0, 0f, t)).isEqualTo(0)
        assertThat(DndLogic.resolveDepth(rows, 1, top("x"), 0, 60f, t)).isEqualTo(1)
    }

    @Test fun subtask_stays_nested_unless_dragged_left() {
        val rows = listOf(top("a"), top("b"))
        assertThat(DndLogic.resolveDepth(rows, 1, sub("x", "a"), 1, 0f, t)).isEqualTo(1)
        assertThat(DndLogic.resolveDepth(rows, 1, sub("x", "a"), 1, -60f, t)).isEqualTo(0)
    }

    @Test fun before_a_subtask_must_be_inside_the_group() {
        val rows = listOf(top("a"), sub("a1", "a"), top("b"))
        assertThat(DndLogic.resolveDepth(rows, 1, top("x"), 0, -60f, t)).isEqualTo(1)
    }

    @Test fun parent_between_subtasks_is_illegal() {
        val rows = listOf(top("a"), sub("a1", "a"), sub("a2", "a"))
        assertThat(DndLogic.resolveDepth(rows, 2, top("p", hasChildren = true), 0, 0f, t)).isNull()
    }

    @Test fun parent_always_stays_top_level() {
        val rows = listOf(top("a"), top("b"))
        assertThat(DndLogic.resolveDepth(rows, 1, top("p", hasChildren = true), 0, 80f, t)).isEqualTo(0)
    }

    @Test fun parent_for_depth_1_is_previous_top_level_or_its_parent() {
        val rows = listOf(top("a"), sub("a1", "a"), top("b"))
        assertThat(DndLogic.parentFor(rows, 1, 1)).isEqualTo("a")
        assertThat(DndLogic.parentFor(rows, 2, 1)).isEqualTo("a")
        assertThat(DndLogic.parentFor(rows, 3, 0)).isNull()
    }

    // --- T5: drop operations ---
    @Test fun nest_target_produces_nest() {
        val rows = listOf(top("a"), top("b"))
        assertThat(DndLogic.dropOperation(rows, rows, "b", "a")).isEqualTo(MoveOperation.Nest("b", "a"))
    }

    @Test fun reorder_uses_open_siblings_as_neighbours() {
        val base = listOf(top("a"), top("b"), top("c"))
        val preview = DndLogic.move(base, "c", 0, 0)
        assertThat(DndLogic.dropOperation(preview, base, "c", null))
            .isEqualTo(MoveOperation.Reorder("c", null, null, "a"))
    }

    @Test fun indent_produces_reorder_into_group() {
        val base = listOf(top("a"), top("b"))
        val preview = DndLogic.move(base, "b", 1, 1)
        assertThat(DndLogic.dropOperation(preview, base, "b", null))
            .isEqualTo(MoveOperation.Reorder("b", "a", null, null))
    }

    @Test fun unnest_produces_top_level_reorder() {
        val base = listOf(top("a", hasChildren = true), sub("a1", "a"), top("b"))
        val preview = DndLogic.move(base, "a1", 1, 0)
        assertThat(DndLogic.dropOperation(preview, base, "a1", null))
            .isEqualTo(MoveOperation.Reorder("a1", null, "a", "b"))
    }

    @Test fun completed_subtasks_are_skipped_as_neighbours() {
        val base = listOf(top("a", hasChildren = true), sub("a1", "a"), sub("done", "a", completed = true), top("x"))
        val preview = DndLogic.move(base, "x", 2, 1)
        val op = DndLogic.dropOperation(preview, base, "x", null) as MoveOperation.Reorder
        assertThat(op.parentId).isEqualTo("a")
        assertThat(op.aboveId).isEqualTo("a1")
        assertThat(op.belowId).isNull()
    }

    @Test fun depth_1_under_collapsed_parent_appends_via_nest() {
        val base = listOf(top("a", hasChildren = true, expanded = false), top("b"))
        val preview = DndLogic.move(base, "b", 1, 1)
        assertThat(DndLogic.dropOperation(preview, base, "b", null)).isEqualTo(MoveOperation.Nest("b", "a"))
    }

    // --- T6: no-op ---
    @Test fun dropping_in_place_emits_nothing() {
        val rows = listOf(top("a"), top("b"), top("c"))
        assertThat(DndLogic.dropOperation(rows, rows, "b", null)).isNull()
    }

    // --- DD-07 / DD-04 / DD-09 ---
    @Test fun dragging_a_parent_hides_its_children() {
        val rows = listOf(top("a", hasChildren = true), sub("a1", "a"), sub("a2", "a"), top("b"))
        val (preview, hidden) = DndLogic.startPreview(rows, "a")
        assertThat(preview.map { it.key }).containsExactly("a", "b").inOrder()
        assertThat(hidden).isEqualTo(2)
    }

    @Test fun nest_zone_is_middle_half_of_open_top_level_rows() {
        assertThat(DndLogic.isNestZone(top("a"), 0.5f)).isTrue()
        assertThat(DndLogic.isNestZone(top("a"), 0.2f)).isFalse()
        assertThat(DndLogic.isNestZone(sub("s", "a"), 0.5f)).isFalse()
    }

    @Test fun auto_scroll_speed_scales_with_edge_proximity() {
        assertThat(DndLogic.autoScrollSpeed(500f, 0f, 1000f, 64f, 1f)).isEqualTo(0f)
        assertThat(DndLogic.autoScrollSpeed(0f, 0f, 1000f, 64f, 1f)).isEqualTo(-20f)
        assertThat(DndLogic.autoScrollSpeed(1000f, 0f, 1000f, 64f, 1f)).isEqualTo(20f)
    }
}
