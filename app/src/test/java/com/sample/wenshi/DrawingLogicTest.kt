package com.sample.wenshi

import androidx.compose.ui.geometry.Offset
import com.sample.wenshi.data.Freehand
import com.sample.wenshi.data.Line
import com.sample.wenshi.data.Rectangle
import com.sample.wenshi.viewmodel.DrawingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单元测试 —— 覆盖 Shape 的碰撞检测 / 平移、DrawingViewModel 的
 * 撤销/重做栈行为，以及 JSON 序列化往返。
 *
 * 这些都是纯 Kotlin 逻辑，不依赖 Android 框架，在 `./gradlew test` 中运行。
 */
class DrawingLogicTest {

    private val black = 0xFF000000L
    private val red = 0xFFFF0000L
    private val green = 0xFF00FF00L

    // ---------- Shape.hitTest ----------

    @Test
    fun `line hitTest - point on segment hits`() {
        val line = Line("1", black, 4f, startX = 0f, startY = 0f, endX = 100f, endY = 0f)
        assertTrue(line.hitTest(Offset(50f, 0f), tolerance = 5f))
        assertTrue(line.hitTest(Offset(50f, 4f), tolerance = 5f)) // 容差内
    }

    @Test
    fun `line hitTest - far point misses`() {
        val line = Line("1", black, 4f, startX = 0f, startY = 0f, endX = 100f, endY = 0f)
        assertFalse(line.hitTest(Offset(50f, 100f), tolerance = 5f))
        assertFalse(line.hitTest(Offset(-50f, 0f), tolerance = 5f)) // 投影落在起点外
    }

    @Test
    fun `rectangle hitTest - inside hits, outside misses`() {
        val rect = Rectangle("1", black, 4f, left = 10f, top = 10f, right = 110f, bottom = 60f)
        assertTrue(rect.hitTest(Offset(60f, 30f), tolerance = 20f))
        assertTrue(rect.hitTest(Offset(5f, 5f), tolerance = 20f)) // 容差边界
        assertFalse(rect.hitTest(Offset(200f, 200f), tolerance = 5f))
    }

    @Test
    fun `freehand hitTest - on polyline hits`() {
        val fh = Freehand(
            "1", black, 4f,
            points = listOf(listOf(0f, 0f), listOf(50f, 0f), listOf(100f, 50f))
        )
        assertTrue(fh.hitTest(Offset(25f, 0f), tolerance = 5f)) // 在第一段上
        assertFalse(fh.hitTest(Offset(200f, 200f), tolerance = 5f))
    }

    @Test
    fun `freehand hitTest - empty or single point never hits`() {
        val empty = Freehand("1", black, 4f, points = emptyList())
        assertFalse(empty.hitTest(Offset(0f, 0f), tolerance = 5f))
        val one = Freehand("2", black, 4f, points = listOf(listOf(0f, 0f)))
        assertFalse(one.hitTest(Offset(0f, 0f), tolerance = 5f))
    }

    // ---------- Shape.translate ----------

    @Test
    fun `line translate - offset applied, original unchanged`() {
        val line = Line("1", black, 4f, 0f, 0f, 10f, 10f)
        val moved = line.translate(5f, 3f) as Line
        assertEquals(5f, moved.startX, 0.001f)
        assertEquals(3f, moved.startY, 0.001f)
        assertEquals(15f, moved.endX, 0.001f)
        assertEquals(13f, moved.endY, 0.001f)
        assertEquals(0f, line.startX, 0.001f) // 原对象不变
    }

    @Test
    fun `rectangle translate - both corners shift`() {
        val rect = Rectangle("1", black, 4f, 0f, 0f, 10f, 10f)
        val moved = rect.translate(5f, 5f) as Rectangle
        assertEquals(5f, moved.left, 0.001f)
        assertEquals(15f, moved.right, 0.001f)
        assertEquals(15f, moved.bottom, 0.001f)
    }

    @Test
    fun `freehand translate - every point shifts`() {
        val fh = Freehand("1", black, 4f, points = listOf(listOf(0f, 0f), listOf(10f, 10f)))
        val moved = fh.translate(3f, 4f) as Freehand
        assertEquals(3f, moved.points[0][0], 0.001f)
        assertEquals(4f, moved.points[0][1], 0.001f)
        assertEquals(13f, moved.points[1][0], 0.001f)
        assertEquals(14f, moved.points[1][1], 0.001f)
    }

    // ---------- Undo / Redo ----------

    @Test
    fun `addShape twice then undo twice clears canvas`() {
        val vm = DrawingViewModel()
        vm.addShape(Line("a", black, 4f, 0f, 0f, 10f, 10f))
        vm.addShape(Line("b", black, 4f, 20f, 20f, 30f, 30f))
        assertEquals(2, vm.state.value.shapes.size)
        assertTrue(vm.state.value.canUndo)

        vm.undo()
        assertEquals(1, vm.state.value.shapes.size)
        assertTrue(vm.state.value.canRedo)

        vm.undo()
        assertEquals(0, vm.state.value.shapes.size)
        assertFalse(vm.state.value.canUndo)
        assertTrue(vm.state.value.canRedo)
    }

    @Test
    fun `redo restores undone shape`() {
        val vm = DrawingViewModel()
        vm.addShape(Line("a", black, 4f, 0f, 0f, 10f, 10f))
        vm.undo()
        assertTrue(vm.state.value.canRedo)
        vm.redo()
        assertEquals(1, vm.state.value.shapes.size)
        assertEquals("a", vm.state.value.shapes[0].id)
        assertFalse(vm.state.value.canRedo)
    }

    @Test
    fun `new addShape after undo clears redo stack`() {
        val vm = DrawingViewModel()
        vm.addShape(Line("a", black, 4f, 0f, 0f, 10f, 10f))
        vm.undo()
        assertTrue(vm.state.value.canRedo)
        vm.addShape(Line("b", black, 4f, 20f, 20f, 30f, 30f))
        assertFalse(vm.state.value.canRedo)
    }

    @Test
    fun `clearAll then undo restores all shapes`() {
        val vm = DrawingViewModel()
        vm.addShape(Line("a", black, 4f, 0f, 0f, 10f, 10f))
        vm.addShape(Line("b", black, 4f, 20f, 20f, 30f, 30f))
        vm.clearAll()
        assertEquals(0, vm.state.value.shapes.size)
        vm.undo()
        assertEquals(2, vm.state.value.shapes.size)
    }

    @Test
    fun `undo on empty stack is a no-op, not a crash`() {
        val vm = DrawingViewModel()
        vm.undo()
        vm.redo()
        assertFalse(vm.state.value.canUndo)
        assertFalse(vm.state.value.canRedo)
    }

    // ---------- JSON 往返 ----------

    @Test
    fun `serialize and deserialize preserves mixed shapes`() {
        val vm = DrawingViewModel()
        vm.addShape(Line("a", black, 4f, 0f, 0f, 10f, 10f))
        vm.addShape(Rectangle("b", red, 6f, 20f, 20f, 40f, 40f))
        vm.addShape(Freehand("c", green, 8f, listOf(listOf(1f, 2f), listOf(3f, 4f))))

        val json = vm.serializeToJson()

        val vm2 = DrawingViewModel()
        val ok = vm2.deserializeFromJson(json)

        assertTrue(ok)
        assertEquals(3, vm2.state.value.shapes.size)
        assertEquals("a", vm2.state.value.shapes[0].id)
        assertEquals(black, vm2.state.value.shapes[0].color)
        assertEquals(4f, vm2.state.value.shapes[0].strokeWidth, 0.001f)
        // 撤销栈应被重置
        assertFalse(vm2.state.value.canUndo)
    }

    @Test
    fun `deserialize invalid JSON returns false without crashing`() {
        val vm = DrawingViewModel()
        val ok = vm.deserializeFromJson("not a json {[")
        assertFalse(ok)
        assertEquals(0, vm.state.value.shapes.size)
    }

    @Test
    fun `deserialize JSON missing required fields returns false`() {
        val vm = DrawingViewModel()
        // 缺 color/strokeWidth/startX... 等必填字段
        val ok = vm.deserializeFromJson("""[{"type":"line","id":"x"}]""")
        assertFalse(ok)
    }
}
