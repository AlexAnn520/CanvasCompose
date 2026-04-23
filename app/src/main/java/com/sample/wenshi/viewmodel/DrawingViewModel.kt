package com.sample.wenshi.viewmodel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import com.sample.wenshi.data.Freehand
import com.sample.wenshi.data.Line
import com.sample.wenshi.data.Rectangle
import com.sample.wenshi.data.Shape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 绘图工具枚举
 *
 * SELECT   - 选择模式：点击选中已有图形，拖拽移动选中的图形
 * LINE     - 直线模式：按下确定起点，拖动确定终点
 * RECTANGLE - 矩形模式：按下确定一个角，拖动确定对角
 * FREEHAND - 圆珠笔模式：按下后持续记录手指轨迹
 */
enum class Tool {
    SELECT, LINE, RECTANGLE, FREEHAND
}

/**
 * 绘图状态 —— 包含画布上所有需要的信息。
 *
 * 为什么用 data class？
 * data class 自动实现 copy()、equals()、hashCode()，非常适合作为不可变状态。
 * Compose 通过对比新旧状态来决定是否需要重新绘制 UI（recomposition）。
 *
 * 为什么用 StateFlow 而不是 mutableStateOf？
 * StateFlow 属于 ViewModel 层，与 Compose UI 解耦，方便测试。
 * Compose 通过 collectAsState() 订阅 StateFlow，状态变化时自动重组。
 *
 * strokeWidth 的单位是 dp（而不是像素），实际绘制时 DrawScope 会通过
 * Density 转换为 px。这样画布在不同屏幕密度下视觉粗细一致，保存的
 * JSON 文件也能跨设备还原。
 */
data class DrawingState(
    val shapes: List<Shape> = emptyList(),     // 当前画布上所有图形
    val selectedShapeId: String? = null,       // 选中的图形 ID，null 表示未选中
    val currentTool: Tool = Tool.FREEHAND,     // 当前使用的工具
    val strokeColor: Color = Color.Black,      // 当前画笔颜色
    val strokeWidth: Float = 6f,              // 当前画笔粗细（单位 dp）
    val canUndo: Boolean = false,              // 是否可以撤销
    val canRedo: Boolean = false               // 是否可以重做
)

/**
 * 绘图 ViewModel —— 管理所有绘图状态和业务逻辑。
 *
 * ViewModel 的生命周期：
 * - 在屏幕旋转等配置变更时不会销毁，状态自动保留
 * - 只在 Activity 真正销毁时才清理
 *
 * 核心设计原则：
 * 1. 不可变状态：每次修改都创建新的 DrawingState，而不是修改旧对象
 * 2. 单一数据源：所有 UI 状态都从 _state 这个 StateFlow 中读取
 * 3. 撤销/重做使用快照模式：每次修改前保存一份 shapes 列表的副本
 */
class DrawingViewModel : ViewModel() {

    // MutableStateFlow 是可变的状态流，内部私有，外部只暴露只读版本
    private val _state = MutableStateFlow(DrawingState())

    // asStateFlow() 将可变流转为只读流，防止外部直接修改状态
    val state: StateFlow<DrawingState> = _state.asStateFlow()

    // 撤销栈：保存每次修改前的 shapes 快照
    private val undoStack = mutableListOf<List<Shape>>()

    // 重做栈：撤销时把当前状态压入，重做时弹出恢复
    private val redoStack = mutableListOf<List<Shape>>()

    // JSON 序列化器，配置为美化输出（方便调试）并忽略未知键（兼容未来版本）
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // ==================== 工具与样式 ====================

    /** 切换当前绘图工具 */
    fun setTool(tool: Tool) {
        _state.update { it.copy(currentTool = tool, selectedShapeId = null) }
    }

    /** 设置画笔颜色。toArgb() 将 Compose Color 转为 ARGB 整数 */
    fun setStrokeColor(color: Color) {
        _state.update { it.copy(strokeColor = color) }
    }

    /** 设置画笔粗细（dp） */
    fun setStrokeWidth(width: Float) {
        _state.update { it.copy(strokeWidth = width) }
    }

    // ==================== 图形操作 ====================

    /**
     * 添加一个新图形到画布。
     * 添加前先保存当前状态到撤销栈，以便后续撤销。
     */
    fun addShape(shape: Shape) {
        pushUndo()
        _state.update { it.copy(shapes = it.shapes + shape) }
    }

    /**
     * 根据触摸位置进行碰撞检测，选中命中的图形。
     * 从后往前遍历（后绘制的图形在上层），命中第一个就停止。
     * tolerance 是碰撞容差（像素），弥补手指触摸的不精确性。
     */
    fun selectShapeAt(point: Offset, tolerance: Float = 20f) {
        val shapes = _state.value.shapes
        // 从后往前查找——列表末尾的图形绘制在最上面
        for (shape in shapes.reversed()) {
            if (shape.hitTest(point, tolerance)) {
                _state.update { it.copy(selectedShapeId = shape.id) }
                return
            }
        }
        // 没有命中任何图形，取消选中
        _state.update { it.copy(selectedShapeId = null) }
    }

    /** 取消选中 */
    fun clearSelection() {
        _state.update { it.copy(selectedShapeId = null) }
    }

    /**
     * 移动选中的图形。
     * 先保存撤销快照，然后用 translate() 创建平移后的新图形替换原图形。
     */
    fun moveSelectedShape(dx: Float, dy: Float) {
        val selectedId = _state.value.selectedShapeId ?: return
        _state.update { state ->
            val newShapes = state.shapes.map { shape ->
                if (shape.id == selectedId) shape.translate(dx, dy) else shape
            }
            state.copy(shapes = newShapes)
        }
    }

    /**
     * 保存移动操作到撤销栈。
     * 因为移动是连续的（每帧都回调），我们在移动开始时才保存快照（由 Canvas 调用）。
     */
    fun pushMoveStart() {
        pushUndo()
    }

    // ==================== 撤销 / 重做 ====================

    /**
     * 将当前 shapes 快照压入撤销栈，清空重做栈。
     * 每次修改操作前调用此方法。
     *
     * 设计选择：快照模式 vs 命令模式
     - 快照模式（当前选择）：保存完整状态，实现简单，内存占用略高
     - 命令模式：只保存操作描述，实现复杂但内存占用低
     - 对于简单绘图应用，快照模式完全够用
     */
    private fun pushUndo() {
        undoStack.add(_state.value.shapes.toList())
        redoStack.clear()
        updateUndoRedoFlags()
    }

    /** 撤销：恢复到上一次操作前的状态 */
    fun undo() {
        if (undoStack.isEmpty()) return
        // 将当前状态压入重做栈
        redoStack.add(_state.value.shapes.toList())
        // 从撤销栈弹出上一个状态并恢复
        // 注：用 removeAt(lastIndex) 而不是 removeLast()。
        // removeLast() 在 compileSdk ≥ 35 时会被绑到 Java 21 的 SequencedCollection.removeLast()，
        // 在 ≤ Android 14 的设备上缺少该方法，运行时抛 NoSuchMethodError。
        val previous = undoStack.removeAt(undoStack.lastIndex)
        _state.update { it.copy(shapes = previous, selectedShapeId = null) }
        updateUndoRedoFlags()
    }

    /** 重做：恢复到撤销前的状态 */
    fun redo() {
        if (redoStack.isEmpty()) return
        // 将当前状态压入撤销栈
        undoStack.add(_state.value.shapes.toList())
        // 从重做栈弹出状态并恢复（同上：避免 removeLast()）
        val next = redoStack.removeAt(redoStack.lastIndex)
        _state.update { it.copy(shapes = next, selectedShapeId = null) }
        updateUndoRedoFlags()
    }

    /** 清空画布 */
    fun clearAll() {
        if (_state.value.shapes.isEmpty()) return
        pushUndo()
        _state.update { it.copy(shapes = emptyList(), selectedShapeId = null) }
    }

    /** 更新 canUndo / canRedo 标志，用于 UI 控制按钮的启用/禁用 */
    private fun updateUndoRedoFlags() {
        _state.update {
            it.copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
    }

    // ==================== 保存 / 加载 ====================

    /**
     * 将当前所有图形序列化为 JSON 字符串。
     * 调用方（MainActivity）负责将此字符串写入文件。
     */
    fun serializeToJson(): String {
        return json.encodeToString(_state.value.shapes)
    }

    /**
     * 从 JSON 字符串反序列化图形列表并替换当前画布。
     * 清空撤销/重做栈，因为加载的是全新内容。
     *
     * @return 成功返回 true；JSON 格式错误返回 false（调用方可据此提示用户）。
     */
    fun deserializeFromJson(jsonString: String): Boolean {
        return try {
            val shapes: List<Shape> = json.decodeFromString(jsonString)
            undoStack.clear()
            redoStack.clear()
            _state.update { DrawingState(shapes = shapes) }
            true
        } catch (e: SerializationException) {
            false
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization 在某些缺字段场景会抛 IllegalArgumentException
            false
        }
    }

    // ==================== 辅助 ====================

    /** 生成唯一 ID，用于标识每个图形 */
    fun generateId(): String = UUID.randomUUID().toString()
}
