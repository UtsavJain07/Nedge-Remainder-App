package app.nudge.core.domain.order

import app.nudge.core.domain.order.OrderingCalculator.GAP
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OrderingCalculatorTest {

    @Test
    fun `top of an empty group is zero`() {
        assertThat(OrderingCalculator.top(null)).isEqualTo(0.0)
    }

    @Test
    fun `top goes one gap above the minimum`() {
        assertThat(OrderingCalculator.top(0.0)).isEqualTo(-GAP)
        assertThat(OrderingCalculator.top(-512.0)).isEqualTo(-512.0 - GAP)
    }

    @Test
    fun `bottom of an empty group is one gap`() {
        assertThat(OrderingCalculator.bottom(null)).isEqualTo(GAP)
    }

    @Test
    fun `bottom goes one gap below the maximum`() {
        assertThat(OrderingCalculator.bottom(2048.0)).isEqualTo(2048.0 + GAP)
    }

    @Test
    fun `between with no neighbours is zero`() {
        assertThat(OrderingCalculator.between(null, null)).isEqualTo(0.0)
    }

    @Test
    fun `between at the top edge is one gap above the item below`() {
        assertThat(OrderingCalculator.between(null, 100.0)).isEqualTo(100.0 - GAP)
    }

    @Test
    fun `between at the bottom edge is one gap below the item above`() {
        assertThat(OrderingCalculator.between(100.0, null)).isEqualTo(100.0 + GAP)
    }

    @Test
    fun `between two items is the midpoint`() {
        assertThat(OrderingCalculator.between(0.0, 1024.0)).isEqualTo(512.0)
        assertThat(OrderingCalculator.between(-3.0, 5.0)).isEqualTo(1.0)
    }

    @Test
    fun `between result sorts strictly between its neighbours`() {
        var above = 0.0
        val below = 1.0
        repeat(20) {
            val mid = OrderingCalculator.between(above, below)
            assertThat(mid).isGreaterThan(above)
            assertThat(mid).isLessThan(below)
            above = mid
        }
    }

    @Test
    fun `renormalize triggers only when the gap is below MIN_GAP`() {
        assertThat(OrderingCalculator.needsRenormalize(1.0, 1.0 + 1e-7)).isTrue()
        assertThat(OrderingCalculator.needsRenormalize(1.0, 1.0)).isTrue()
        assertThat(OrderingCalculator.needsRenormalize(1.0, 1.0 + 1e-5)).isFalse()
        assertThat(OrderingCalculator.needsRenormalize(0.0, 1024.0)).isFalse()
    }

    @Test
    fun `renormalize is never needed at an edge`() {
        assertThat(OrderingCalculator.needsRenormalize(null, 1.0)).isFalse()
        assertThat(OrderingCalculator.needsRenormalize(1.0, null)).isFalse()
        assertThat(OrderingCalculator.needsRenormalize(null, null)).isFalse()
    }

    @Test
    fun `renormalize keeps the given order with even gaps`() {
        val result = OrderingCalculator.renormalize(listOf("c", "a", "b"))

        assertThat(result).containsExactly("c", 0.0, "a", GAP, "b", 2 * GAP)
        assertThat(result.entries.sortedBy { it.value }.map { it.key }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `renormalize of an empty list is empty`() {
        assertThat(OrderingCalculator.renormalize(emptyList())).isEmpty()
    }

    @Test
    fun `repeated midpoint inserts eventually need renormalize`() {
        var above = 0.0
        val below = 1024.0
        var steps = 0
        while (!OrderingCalculator.needsRenormalize(above, below)) {
            above = OrderingCalculator.between(above, below)
            steps++
        }
        // 1024 / 2^n < 1e-6 → about 30 halvings; well within Double precision.
        assertThat(steps).isAtLeast(25)
        assertThat(steps).isAtMost(40)
    }
}
