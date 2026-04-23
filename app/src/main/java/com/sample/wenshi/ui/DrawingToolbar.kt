package com.sample.wenshi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sample.wenshi.viewmodel.Tool

/**
 * 绘图工具栏 —— 提供工具选择、颜色/粗细设置、撤销重做和文件操作按钮。
 *
 * @param currentTool    当前选中的工具
 * @param strokeColor    当前画笔颜色
 * @param strokeWidth    当前画笔粗细
 * @param canUndo        是否可撤销
 * @param canRedo        是否可重做
 * @param onToolChange   切换工具的回调
 * @param onColorChange  改变颜色的回调
 * @param onStrokeWidthChange 改变粗细的回调
 * @param onUndo         撤销回调
 * @param onRedo         重做回调
 * @param onClear        清空画布回调
 * @param onSave         保存文件回调
 * @param onOpen         打开文件回调
 *
 * Compose 要点：
 * - @Composable 注解表明这是一个可组合函数，只能在其他 @Composable 函数中调用
 * - 所有状态都通过参数传入（单向数据流），事件通过回调传出
 * - 这让组件易于测试和复用
 */
@Composable
fun DrawingToolbar(
    modifier: Modifier = Modifier,
    currentTool: Tool,
    strokeColor: Color,
    strokeWidth: Float,
    canUndo: Boolean,
    canRedo: Boolean,
    onToolChange: (Tool) -> Unit,
    onColorChange: (Color) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ===== 第一行：工具选择按钮 =====
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("工具:", fontSize = 14.sp)
            ToolButton("选择", Tool.SELECT, currentTool, onToolChange)
            ToolButton("直线", Tool.LINE, currentTool, onToolChange)
            ToolButton("矩形", Tool.RECTANGLE, currentTool, onToolChange)
            ToolButton("画笔", Tool.FREEHAND, currentTool, onToolChange)
        }

        // ===== 第二行：颜色选择 =====
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("颜色:", fontSize = 14.sp)
            // 预设的几种颜色，覆盖常用需求
            val colors = listOf(
                Color.Black, Color.DarkGray, Color.Red, Color(0xFF2196F3),
                Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF9C27B0), Color.White
            )
            for (color in colors) {
                ColorSwatch(
                    color = color,
                    isSelected = color == strokeColor,
                    onClick = { onColorChange(color) }
                )
            }
        }

        // ===== 第三行：粗细 + 撤销/重做 + 文件操作 =====
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("粗细:", fontSize = 14.sp)
            // 三档粗细选择。数值单位为 dp（Canvas 绘制时通过 Density 转 px），
            // 保证在不同屏幕密度下视觉粗细一致，也使三档差异肉眼可辨。
            for (widthDp in listOf(2f, 6f, 14f)) {
                val label = when (widthDp) {
                    2f -> "细"
                    6f -> "中"
                    else -> "粗"
                }
                StrokeWidthButton(
                    label = label,
                    isSelected = widthDp == strokeWidth,
                    onClick = { onStrokeWidthChange(widthDp) }
                )
            }

            Spacer(Modifier.width(8.dp))

            // 撤销/重做按钮
            // enabled = false 时按钮变灰且不可点击
            Button(onClick = onUndo, enabled = canUndo) {
                Text("撤销")
            }
            Button(onClick = onRedo, enabled = canRedo) {
                Text("重做")
            }

            Spacer(Modifier.width(8.dp))

            // 文件操作
            OutlinedButton(onClick = onClear) { Text("清空") }
            OutlinedButton(onClick = onSave) { Text("保存") }
            OutlinedButton(onClick = onOpen) { Text("打开") }
        }
    }
}

/**
 * 工具选择按钮 —— 选中时使用实心样式，未选中时使用描边样式。
 *
 * Compose 要点：
 * - Button vs OutlinedButton：前者有背景填充，后者只有边框
 * - 通过条件判断选择不同的按钮样式，比自定义 ButtonColors 更直观
 */
@Composable
private fun ToolButton(
    label: String,
    tool: Tool,
    currentTool: Tool,
    onSelect: (Tool) -> Unit
) {
    if (tool == currentTool) {
        Button(
            onClick = { onSelect(tool) },
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(label, fontSize = 13.sp)
        }
    } else {
        OutlinedButton(
            onClick = { onSelect(tool) },
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(label, fontSize = 13.sp)
        }
    }
}

/**
 * 颜色色块 —— 小圆形色块，选中时显示边框。
 *
 * Compose 要点：
 * - Box 是最简单的布局容器，用于叠加子元素（这里只有背景色+边框）
 * - Modifier 链式调用：clip(CircleShape) 裁剪为圆形，clickable 添加点击事件
 * - border 只在选中时显示，通过条件判断决定是否添加 modifier
 */
@Composable
private fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val size = 28.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)                    // 裁剪为圆形
            .background(color)                    // 填充颜色
            .then(
                // 选中时显示白色边框（3dp），未选中时显示灰色细边框（1dp）
                if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                else Modifier.border(1.dp, Color.Gray, CircleShape)
            )
            .clickable(onClick = onClick)         // 添加点击处理
    )
}

/**
 * 粗细选择按钮 —— 类似 ToolButton，用 OutlinedButton 的选中/未选中切换。
 */
@Composable
private fun StrokeWidthButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    if (isSelected) {
        Button(onClick = onClick) { Text(label, fontSize = 12.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label, fontSize = 12.sp) }
    }
}
