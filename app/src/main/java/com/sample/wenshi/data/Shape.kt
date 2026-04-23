package com.sample.wenshi.data

import androidx.compose.ui.geometry.Offset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 图形基类 —— 使用 sealed class 确保所有图形类型在此文件中穷举。
 *
 * 为什么用 sealed class？
 * 1. 编译器会检查 when 表达式是否覆盖所有子类，防止遗漏
 * 2. 所有子类必须在同一文件中定义，方便维护
 *
 * 为什么用 @Serializable？
 * 为了将图形列表保存为 JSON 文件 / 从 JSON 文件加载。
 * kotlinx.serialization 通过 @SerialName 区分多态类型（JSON 中用 "type": "line" 等）。
 *
 * 为什么 color 用 Long 而不是 Compose 的 Color？
 * Compose 的 Color 不支持直接序列化，Long 可以无损存储 ARGB 值。
 */
@Serializable
sealed class Shape {
    abstract val id: String
    abstract val color: Long        // ARGB 颜色值，用 Long 存储以便 JSON 序列化
    abstract val strokeWidth: Float // 线条粗细

    /**
     * 判断给定点是否"命中"了这个图形。
     * tolerance 是容差（像素），因为手指触摸不够精确。
     * 每个子类需要实现自己的碰撞检测算法。
     */
    abstract fun hitTest(point: Offset, tolerance: Float): Boolean

    /**
     * 将图形平移 (dx, dy) 像素，返回一个新的图形对象。
     * 因为 data class 是不可变的（immutable），所以返回新对象而不是修改原对象。
     * 不可变性让撤销/重做更安全——不用担心旧状态被意外修改。
     */
    abstract fun translate(dx: Float, dy: Float): Shape
}

/**
 * 直线图形
 *
 * @param startX 起点X坐标
 * @param startY 起点Y坐标
 * @param endX   终点X坐标
 * @param endY   终点Y坐标
 *
 * 为什么坐标用 Float 而不是 Compose 的 Offset？
 * Offset 不能直接被 kotlinx.serialization 序列化，拆成 Float 更简单。
 */
@Serializable
@SerialName("line")  // JSON 中用 "type": "line" 标识此子类
data class Line(
    override val id: String,
    override val color: Long,
    override val strokeWidth: Float,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
) : Shape() {

    /**
     * 直线的碰撞检测：计算点到线段的距离，小于容差则命中。
     * 使用向量投影法求最短距离。
     */
    override fun hitTest(point: Offset, tolerance: Float): Boolean {
        val start = Offset(startX, startY)
        val end = Offset(endX, endY)
        return distancePointToSegment(point, start, end) <= tolerance
    }

    override fun translate(dx: Float, dy: Float): Shape = copy(
        startX = startX + dx,
        startY = startY + dy,
        endX = endX + dx,
        endY = endY + dy
    )
}

/**
 * 矩形图形
 *
 * 用左上角 + 右下角两点定义矩形，这与用户拖拽绘制的交互方式天然对应。
 */
@Serializable
@SerialName("rectangle")
data class Rectangle(
    override val id: String,
    override val color: Long,
    override val strokeWidth: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) : Shape() {

    /**
     * 矩形的碰撞检测：判断点是否在矩形区域内（含边框宽度容差）。
     * 注意：绘制时用户可能从右下往左上拖，所以需要 min/max 确保坐标有序。
     */
    override fun hitTest(point: Offset, tolerance: Float): Boolean {
        val rectLeft = min(left, right) - tolerance
        val rectTop = min(top, bottom) - tolerance
        val rectRight = max(left, right) + tolerance
        val rectBottom = max(top, bottom) + tolerance
        return point.x in rectLeft..rectRight && point.y in rectTop..rectBottom
    }

    override fun translate(dx: Float, dy: Float): Shape = copy(
        left = left + dx,
        top = top + dy,
        right = right + dx,
        bottom = bottom + dy
    )
}

/**
 * 自由绘制（圆珠笔）图形
 *
 * 存储用户手指经过的所有点，绘制时用线段依次连接这些点。
 * points 是一个二维列表：List<Float> = [x, y]，为了 JSON 序列化方便。
 *
 * 为什么不用 List<Offset>？
 * Offset 不支持序列化，用 List<Float> 的列表嵌套在 JSON 中表现为 [[x1,y1],[x2,y2],...]
 */
@Serializable
@SerialName("freehand")
data class Freehand(
    override val id: String,
    override val color: Long,
    override val strokeWidth: Float,
    val points: List<List<Float>>   // 每个元素是 [x, y]
) : Shape() {

    /**
     * 自由绘制的碰撞检测：遍历所有相邻点对（构成线段），
     * 如果触摸点到任意一段线段的距离小于容差，则命中。
     * 对于长笔画这会是 O(n) 操作，但简单应用够用。
     */
    override fun hitTest(point: Offset, tolerance: Float): Boolean {
        if (points.size < 2) return false
        for (i in 0 until points.size - 1) {
            val p1 = Offset(points[i][0], points[i][1])
            val p2 = Offset(points[i + 1][0], points[i + 1][1])
            if (distancePointToSegment(point, p1, p2) <= tolerance) return true
        }
        return false
    }

    override fun translate(dx: Float, dy: Float): Shape = copy(
        points = points.map { listOf(it[0] + dx, it[1] + dy) }
    )
}

// ==================== 几何工具函数 ====================

/**
 * 计算点 p 到线段 (a→b) 的最短距离。
 *
 * 算法原理：
 * 1. 将点 p 投影到线段所在直线上
 * 2. 如果投影点在线段上，返回垂直距离
 * 3. 如果投影点在线段外，返回到最近端点的距离
 */
private fun distancePointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    // 线段长度为 0（起点=终点），直接算点到点距离
    if (dx == 0f && dy == 0f) return distance(p, a)
    // t 是投影参数：0 表示投影在 a 点，1 表示在 b 点
    val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
    // 将 t 钳制到 [0, 1] 范围，确保投影点在线段上
    val tClamped = t.coerceIn(0f, 1f)
    // 计算投影点坐标
    val projX = a.x + tClamped * dx
    val projY = a.y + tClamped * dy
    return distance(p, Offset(projX, projY))
}

/** 两点之间的欧几里得距离 */
private fun distance(a: Offset, b: Offset): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
