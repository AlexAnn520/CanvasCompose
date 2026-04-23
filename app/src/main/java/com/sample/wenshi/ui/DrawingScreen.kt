package com.sample.wenshi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sample.wenshi.data.Freehand
import com.sample.wenshi.data.Line
import com.sample.wenshi.data.Rectangle
import com.sample.wenshi.viewmodel.DrawingViewModel

/**
 * 绘图主屏幕 —— 将工具栏和画布组合在一起。
 *
 * @param viewModel 绘图 ViewModel，由 viewModel() 函数自动创建或获取。
 *                  在 Activity 配置变更（如旋转屏幕）时，ViewModel 会自动保留。
 * @param onSave    保存文件的回调（由 MainActivity 提供 SAF 文件选择器）
 * @param onOpen    打开文件的回调（由 MainActivity 提供 SAF 文件选择器）
 *
 * Compose 要点：
 * - Surface 提供了 Material Design 的背景色和阴影
 * - Column 纵向排列子组件：工具栏在上，画布在下
 * - collectAsState() 将 StateFlow 转为 Compose State，状态变化时自动重组 UI
 */
@Composable
fun DrawingScreen(
    viewModel: DrawingViewModel = viewModel(),  // viewModel() 自动管理 ViewModel 生命周期
    onSave: () -> Unit,
    onOpen: () -> Unit,
) {
    // collectAsState() 订阅 ViewModel 的 StateFlow。
    // 当 state 值改变时，此 Composable 会自动重组（recompose）。
    // "by" 是 Kotlin 的属性委托语法，让我们直接用 state.shapes 而不是 state.value.shapes
    val state by viewModel.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            // 工具栏区域：固定在顶部，带内边距
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 4.dp,  // 底部阴影，与画布区域分隔
                tonalElevation = 2.dp
            ) {
                DrawingToolbar(
                    currentTool = state.currentTool,
                    strokeColor = state.strokeColor,
                    strokeWidth = state.strokeWidth,
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onToolChange = { viewModel.setTool(it) },
                    onColorChange = { viewModel.setStrokeColor(it) },
                    onStrokeWidthChange = { viewModel.setStrokeWidth(it) },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    onClear = { viewModel.clearAll() },
                    onSave = onSave,
                    onOpen = onOpen,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // 画布区域：填满剩余空间
            DrawingCanvas(
                shapes = state.shapes,
                currentTool = state.currentTool,
                strokeColor = state.strokeColor,
                strokeWidth = state.strokeWidth,
                selectedShapeId = state.selectedShapeId,
                onAddShape = { shape ->
                    // ViewModel.generateId() 创建唯一 ID，然后添加图形
                    val shapeWithId = when (shape) {
                        is Line -> shape.copy(id = viewModel.generateId())
                        is Rectangle -> shape.copy(id = viewModel.generateId())
                        is Freehand -> shape.copy(id = viewModel.generateId())
                    }
                    viewModel.addShape(shapeWithId)
                },
                onSelectShapeAt = { viewModel.selectShapeAt(it) },
                onMoveShape = { dx, dy -> viewModel.moveSelectedShape(dx, dy) },
                onMoveStart = { viewModel.pushMoveStart() },
                onClearSelection = { viewModel.clearSelection() }
            )
        }
    }
}
