package com.sample.wenshi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.sample.wenshi.data.Freehand
import com.sample.wenshi.data.Line
import com.sample.wenshi.data.Rectangle
import com.sample.wenshi.data.Shape
import com.sample.wenshi.viewmodel.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 绘图画布 —— 负责显示所有图形和处理用户触摸手势。
 *
 * @param shapes        当前画布上的所有图形
 * @param currentTool   当前使用的工具
 * @param strokeColor   当前画笔颜色
 * @param strokeWidth   当前画笔粗细
 * @param selectedShapeId 选中的图形 ID
 * @param onAddShape    添加图形的回调
 * @param onSelectShapeAt 通过坐标选中图形的回调
 * @param onMoveShape   移动选中图形的回调（dx, dy 是增量）
 * @param onMoveStart   移动开始前的回调（用于保存撤销快照）
 * @param onClearSelection 取消选中的回调
 *
 * Compose 要点：
 * - Canvas 是 Compose 的低级绘制 API，类似传统 View 系统的 onDraw()
 * - pointerInput 修饰符用于处理触摸手势，替代传统 View 的 onTouchEvent()
 * - remember 用于在重组间保持状态（不会因父组件重组而丢失）
 */
@Composable
fun DrawingCanvas(
    shapes: List<Shape>,
    currentTool: Tool,
    strokeColor: Color,
    strokeWidth: Float,
    selectedShapeId: String?,
    onAddShape: (Shape) -> Unit,
    onSelectShapeAt: (Offset) -> Unit,
    onMoveShape: (Float, Float) -> Unit,
    onMoveStart: () -> Unit,
    onClearSelection: () -> Unit,
) {
    // ----- 为 pointerInput 闭包捕获最新值 -----
    //
    // 坑点：pointerInput(currentTool) { ... } 只有在 key 变化时才重建内部 lambda。
    // 如果用户改了 strokeWidth / strokeColor 但工具没切，lambda 里捕获的仍是旧值，
    // onDragEnd 里创建的 Shape 会带着旧的粗细/颜色——导致"预览看起来对，提交后还原始粗细"。
    // rememberUpdatedState 让闭包每次读时都能拿到最新值，同时不会引起 pointerInput 重建。
    val latestStrokeWidth by rememberUpdatedState(strokeWidth)
    val latestStrokeColor by rememberUpdatedState(strokeColor)
    val latestSelectedShapeId by rememberUpdatedState(selectedShapeId)

    // ----- 绘图过程中的临时状态 -----

    // remember { mutableStateOf(...) } 创建一个跨重组持久化的可变状态。
    // 当这些值改变时，Compose 会自动重组（recompose）此 Composable。
    // 与 ViewModel 中 StateFlow 的区别：这些是 UI 局部的临时状态，不需要 ViewModel 管理。

    // 直线/矩形的起始点
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    // 直线/矩形拖拽过程中的当前位置（用于实时预览）
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    // 自由绘制过程中收集的点
    var freehandPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // 是否正在移动已有图形（用于区分"拖拽绘制新图形"和"拖拽移动选中图形"）
    var isMoving by remember { mutableStateOf(false) }
    // 移动是否已经开始记录（防止每次 onDrag 都调用 onMoveStart）
    var moveStarted by remember { mutableStateOf(false) }

    // 将 dp 转为像素，用于碰撞检测的容差
    val hitTolerance = with(LocalDensity.current) { 20.dp.toPx() }

    // 选中图形的高亮虚线框宽度
    val selectionStrokeWidth = with(LocalDensity.current) { 2.dp.toPx() }

    // ===== 画布组件 =====
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)  // 白色画布背景
            // pointerInput 是 Compose 的手势处理核心 API
            // key 参数（currentTool）改变时，手势处理器会重新初始化
            // 这样切换工具后手势行为自动切换
            .pointerInput(currentTool) {
                when (currentTool) {
                    // ----- 选择模式 -----
                    Tool.SELECT -> {
                        // detectDragGestures 同时处理按下和拖拽：
                        // onTap 完成：点击选中
                        // onDragStart + onDrag：拖拽移动
                        detectDragGestures(
                            onDragStart = { offset ->
                                // 先尝试选中
                                onSelectShapeAt(offset)
                                // 如果选中了图形，进入移动模式（读 latest 避免捕获旧值）
                                isMoving = latestSelectedShapeId != null
                                moveStarted = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()  // 消费事件，防止上层处理
                                if (isMoving && latestSelectedShapeId != null) {
                                    // 第一次拖动时保存撤销快照
                                    if (!moveStarted) {
                                        onMoveStart()
                                        moveStarted = true
                                    }
                                    // 按拖动增量移动图形
                                    onMoveShape(dragAmount.x, dragAmount.y)
                                }
                            },
                            onDragEnd = {
                                isMoving = false
                                moveStarted = false
                            },
                            onDragCancel = {
                                isMoving = false
                                moveStarted = false
                            }
                        )
                    }

                    // ----- 直线绘制模式 -----
                    Tool.LINE -> detectDragGestures(
                        onDragStart = { offset ->
                            // 手指按下：记录起点
                            dragStart = offset
                            dragCurrent = offset
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // 手指移动：更新当前位置（用于实时预览）
                            dragCurrent = (dragCurrent ?: dragStart!!) + dragAmount
                        },
                        onDragEnd = {
                            // 手指抬起：创建直线图形并添加到画布
                            val start = dragStart
                            val end = dragCurrent
                            if (start != null && end != null) {
                                // 只有拖动距离足够才算有效绘制（防止误触）
                                if (distance(start, end) > 5f) {
                                    onAddShape(Line(
                                        id = "", // ViewModel 会生成 ID
                                        color = latestStrokeColor.toArgb().toLong(),
                                        strokeWidth = latestStrokeWidth,
                                        startX = start.x,
                                        startY = start.y,
                                        endX = end.x,
                                        endY = end.y
                                    ))
                                }
                            }
                            dragStart = null
                            dragCurrent = null
                        },
                        onDragCancel = {
                            dragStart = null
                            dragCurrent = null
                        }
                    )

                    // ----- 矩形绘制模式 -----
                    Tool.RECTANGLE -> detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragCurrent = offset
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragCurrent = (dragCurrent ?: dragStart!!) + dragAmount
                        },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragCurrent
                            if (start != null && end != null) {
                                if (distance(start, end) > 5f) {
                                    onAddShape(Rectangle(
                                        id = "",
                                        color = latestStrokeColor.toArgb().toLong(),
                                        strokeWidth = latestStrokeWidth,
                                        left = start.x,
                                        top = start.y,
                                        right = end.x,
                                        bottom = end.y
                                    ))
                                }
                            }
                            dragStart = null
                            dragCurrent = null
                        },
                        onDragCancel = {
                            dragStart = null
                            dragCurrent = null
                        }
                    )

                    // ----- 自由绘制（画笔）模式 -----
                    Tool.FREEHAND -> detectDragGestures(
                        onDragStart = { offset ->
                            // 开始新笔画，记录第一个点
                            freehandPoints = listOf(offset)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // 持续追加手指经过的点
                            freehandPoints = freehandPoints + (freehandPoints.last() + dragAmount)
                        },
                        onDragEnd = {
                            // 手指抬起：将所有点转换为 Freehand 图形
                            if (freehandPoints.size >= 2) {
                                onAddShape(Freehand(
                                    id = "",
                                    color = latestStrokeColor.toArgb().toLong(),
                                    strokeWidth = latestStrokeWidth,
                                    points = freehandPoints.map { listOf(it.x, it.y) }
                                ))
                            }
                            freehandPoints = emptyList()
                        },
                        onDragCancel = {
                            freehandPoints = emptyList()
                        }
                    )
                }
            }
    ) {
        // ===== 绘制所有已确认的图形 =====
        for (shape in shapes) {
            drawShape(shape, isSelected = shape.id == selectedShapeId, selectionStrokeWidth)
        }

        // ===== 绘制正在绘制中的预览图形 =====
        // 预览使用半透明（alpha=0.5f），让用户区分"正在绘制"和"已确认"
        val previewAlpha = 0.5f

        // 直线预览。strokeWidth 单位为 dp，DrawScope 实现 Density，可直接 .dp.toPx() 转为像素。
        if (dragStart != null && dragCurrent != null && currentTool == Tool.LINE) {
            drawLine(
                color = strokeColor.copy(alpha = previewAlpha),
                start = dragStart!!,
                end = dragCurrent!!,
                strokeWidth = strokeWidth.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 矩形预览
        if (dragStart != null && dragCurrent != null && currentTool == Tool.RECTANGLE) {
            drawRect(
                color = strokeColor.copy(alpha = previewAlpha),
                topLeft = Offset(
                    min(dragStart!!.x, dragCurrent!!.x),
                    min(dragStart!!.y, dragCurrent!!.y)
                ),
                size = Size(
                    abs(dragCurrent!!.x - dragStart!!.x),
                    abs(dragCurrent!!.y - dragStart!!.y)
                ),
                style = Stroke(width = strokeWidth.dp.toPx())
            )
        }

        // 自由绘制预览
        if (freehandPoints.size >= 2 && currentTool == Tool.FREEHAND) {
            drawPath(
                path = pointsToPath(freehandPoints),
                color = strokeColor.copy(alpha = previewAlpha),
                style = Stroke(
                    width = strokeWidth.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

// ==================== 绘制辅助函数 ====================

/**
 * 在 DrawScope 上绘制一个图形。
 *
 * DrawScope 是 Canvas 提供的绘制环境，包含 drawLine、drawRect、drawPath 等绘制方法。
 * 这就像传统 Android 的 Canvas + Paint 组合，但使用 Kotlin DSL 风格更简洁。
 */
private fun DrawScope.drawShape(shape: Shape, isSelected: Boolean, selectionStrokeWidth: Float) {
    val color = Color(shape.color)

    when (shape) {
        is Line -> {
            // drawLine 画直线，start/end 是两端点坐标。strokeWidth 按 dp 存，绘制时转 px。
            drawLine(
                color = color,
                start = Offset(shape.startX, shape.startY),
                end = Offset(shape.endX, shape.endY),
                strokeWidth = shape.strokeWidth.dp.toPx(),
                cap = StrokeCap.Round  // 圆头线帽，让线段端点更美观
            )
        }
        is Rectangle -> {
            // drawRect 画矩形，需要左上角坐标和尺寸
            // 注意：用户可能从任何方向拖拽，所以需要 min/max 确定左上角
            val left = min(shape.left, shape.right)
            val top = min(shape.top, shape.bottom)
            val right = max(shape.left, shape.right)
            val bottom = max(shape.top, shape.bottom)
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = shape.strokeWidth.dp.toPx())  // Stroke = 空心，Fill = 实心
            )
        }
        is Freehand -> {
            if (shape.points.size >= 2) {
                // drawPath 画自由曲线，通过 Path 对象连接所有点
                val offsets = shape.points.map { Offset(it[0], it[1]) }
                drawPath(
                    path = pointsToPath(offsets),
                    color = color,
                    style = Stroke(
                        width = shape.strokeWidth.dp.toPx(),
                        cap = StrokeCap.Round,     // 圆头，让笔画起止点更自然
                        join = StrokeJoin.Round    // 圆角连接，让笔画转弯更平滑
                    )
                )
            }
        }
    }

    // 选中高亮：在选中图形周围画一个虚线矩形
    if (isSelected) {
        val bounds = getShapeBounds(shape)
        val padding = 8f
        drawRect(
            color = Color(0xFF4488FF),
            topLeft = Offset(bounds.first.x - padding, bounds.first.y - padding),
            size = Size(
                bounds.second.x - bounds.first.x + padding * 2,
                bounds.second.y - bounds.first.y + padding * 2
            ),
            style = Stroke(width = selectionStrokeWidth)
        )
    }
}

/**
 * 计算图形的包围盒（左上角、右下角）。
 * 用于选中时绘制高亮框。
 */
private fun getShapeBounds(shape: Shape): Pair<Offset, Offset> = when (shape) {
    is Line -> Pair(
        Offset(min(shape.startX, shape.endX), min(shape.startY, shape.endY)),
        Offset(max(shape.startX, shape.endX), max(shape.startY, shape.endY))
    )
    is Rectangle -> Pair(
        Offset(min(shape.left, shape.right), min(shape.top, shape.bottom)),
        Offset(max(shape.left, shape.right), max(shape.top, shape.bottom))
    )
    is Freehand -> {
        if (shape.points.isEmpty()) Pair(Offset.Zero, Offset.Zero)
        else Pair(
            Offset(shape.points.minOf { it[0] }, shape.points.minOf { it[1] }),
            Offset(shape.points.maxOf { it[0] }, shape.points.maxOf { it[1] })
        )
    }
}

/**
 * 将点列表转换为 Compose Path 对象。
 * Path 是描述几何路径的类，用 moveTo 定位起点，lineTo 画线到下一个点。
 */
private fun pointsToPath(points: List<Offset>): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points.first().x, points.first().y)  // 移动到第一个点
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)  // 依次连线
        }
    }
}

/** 两点距离 */
private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
