# WenShi 绘图应用 — 开发总结文档

## 一、整体架构

```
┌──────────────────────────────────────┐
│          MainActivity (入口)          │
│  - enableEdgeToEdge 全屏显示          │
│  - 注册 SAF 文件保存/打开启动器        │
│  - setContent → DrawingApp()          │
└───────────────┬──────────────────────┘
                │
┌───────────────▼──────────────────────┐
│         DrawingScreen (主屏幕)         │
│  ┌─ DrawingToolbar (工具栏) ─────────┐ │
│  │ 工具选择 | 颜色 | 粗细 | 撤销重做  │ │
│  │ 保存 | 打开 | 清空               │ │
│  └──────────────────────────────────┘ │
│  ┌─ DrawingCanvas (画布) ────────────┐ │
│  │ Canvas 绘制 + pointerInput 手势    │ │
│  │ 支持绘制预览和选中高亮             │ │
│  └──────────────────────────────────┘ │
└───────────────┬──────────────────────┘
                │ 状态订阅 (collectAsState)
┌───────────────▼──────────────────────┐
│       DrawingViewModel (状态管理)      │
│  - shapes: List<Shape>                │
│  - undoStack / redoStack              │
│  - addShape / selectShapeAt / undo... │
│  - serializeToJson / deserializeFrom  │
└───────────────┬──────────────────────┘
                │
┌───────────────▼──────────────────────┐
│     Shape (数据模型, sealed class)     │
│  ├─ Line   (直线: 起点+终点)          │
│  ├─ Rectangle (矩形: 左上+右下)       │
│  └─ Freehand (自由绘制: 点列表)       │
└──────────────────────────────────────┘
```

### 数据流向（单向数据流）

```
用户操作 → ViewModel 方法 → 更新 StateFlow → Compose 自动重组 UI
```

- **用户操作**（触摸画布、点击按钮）通过回调函数通知 ViewModel
- **ViewModel** 修改内部状态（StateFlow），不直接操作 UI
- **UI 组件**通过 `collectAsState()` 订阅状态，状态变化时自动重组

这是 Compose 推荐的架构模式（Unidirectional Data Flow），优点：
1. 状态变化可预测、易调试
2. UI 只是状态的映射，不持有业务逻辑
3. ViewModel 在屏幕旋转时自动保留

## 二、核心 Compose 概念

### 1. @Composable 注解

标记一个函数为"可组合函数"。特点：
- 只能在其他 @Composable 函数中调用
- 没有返回值（描述 UI 而非返回 UI）
- 可以被框架随时重新执行（重组）

### 2. remember 和 mutableStateOf

```kotlin
var dragStart by remember { mutableStateOf<Offset?>(null) }
```

- `remember`：在重组间保持值，不会因父组件重组而丢失
- `mutableStateOf`：创建一个可被 Compose 观察的状态。值变化时自动触发重组
- `by` 委托：让读写像普通变量一样使用（`dragStart = offset` 而非 `dragStart.value = offset`）

**与 ViewModel 中 StateFlow 的区别：**
- `mutableStateOf`：UI 局部的临时状态（如拖拽预览），只在当前 Composable 中使用
- `StateFlow`：全局业务状态（如画布上的图形列表），需要在多个组件间共享

### 3. Canvas

```kotlin
Canvas(modifier = Modifier.fillMaxSize()) {
    drawLine(color, start, end, strokeWidth)
    drawRect(color, topLeft, size, style = Stroke(width))
    drawPath(path, color, style = Stroke(width))
}
```

- Canvas 是 Compose 的低级绘制 API，相当于传统 View 的 `onDraw()`
- 在 `DrawScope` 中可以使用 `drawLine`、`drawRect`、`drawPath` 等绘制方法
- 每次重组时 Canvas 会完整重绘，所以不需要手动管理"脏区域"

### 4. pointerInput 手势处理

```kotlin
Modifier.pointerInput(currentTool) {
    detectDragGestures(
        onDragStart = { /* 手指按下 */ },
        onDrag = { change, dragAmount -> /* 手指移动 */ },
        onDragEnd = { /* 手指抬起 */ }
    )
}
```

- `pointerInput` 是 Compose 的手势处理核心，替代传统 `onTouchEvent()`
- key 参数（如 `currentTool`）变化时自动重新注册手势处理器
- `detectDragGestures` 内置了拖拽手势检测
- `change.consume()` 表示已处理此触摸事件，不再向上传递

### 5. collectAsState

```kotlin
val state by viewModel.state.collectAsState()
```

将 `StateFlow<DrawingState>` 转为 Compose 的 `State<DrawingState>`。
当 Flow 发射新值时，自动触发所在 Composable 的重组。

### 6. ViewModel 与 viewModel()

```kotlin
val viewModel: DrawingViewModel = viewModel()
```

- `viewModel()` 函数自动创建或获取与当前 Composable 生命周期绑定的 ViewModel
- Activity 配置变更（如旋转屏幕）时，ViewModel 不销毁，状态自动保留
- Activity 真正销毁时，ViewModel 才会调用 `onCleared()` 清理

## 三、功能实现原理

### 1. 图形绘制

每种图形的绘制流程相同：

```
手指按下 → 记录起点 → 手指移动(实时预览) → 手指抬起(确认图形)
```

**直线/矩形**：起终点确定，拖拽中显示半透明预览
**自由绘制**：持续收集手指经过的点，拖拽中显示半透明路径

预览原理：在 Canvas 的绘制代码中，除了绘制 `shapes` 列表中的已确认图形，
还根据临时状态（`dragStart`/`dragCurrent`/`freehandPoints`）绘制预览图形，
预览使用 50% 透明度以区分。

### 2. 图形选中与移动

**选中（碰撞检测）：**
- `Shape.hitTest(point, tolerance)` 计算触摸点是否在图形附近
- 直线：点到线段的最短距离 ≤ 容差
- 矩形：点是否在扩展后的矩形范围内
- 自由绘制：点到任意一段线段的距离 ≤ 容差
- 从后往前遍历图形列表（后绘制的在上层），命中第一个即停止

**移动：**
- 选中后拖拽，每帧调用 `shape.translate(dx, dy)` 生成新图形
- `translate` 返回新对象（不可变），替换列表中的原图形
- 第一次拖拽前保存撤销快照

### 3. 撤销/重做

使用**快照模式**：

```
操作前 → pushUndo() → 保存当前 shapes 列表到 undoStack
操作后 → shapes 列表已更新

撤销 → 当前 shapes 压入 redoStack，从 undoStack 弹出恢复
重做 → 当前 shapes 压入 undoStack，从 redoStack 弹出恢复
```

每次新操作时清空 redoStack（新操作后无法重做）。

### 4. 文档保存/打开

**保存流程：**
```
用户点击"保存" → SAF 弹出保存对话框 → 用户选择位置
→ ViewModel.serializeToJson() 将 shapes 序列化为 JSON
→ 通过 ContentResolver 写入文件
```

**打开流程：**
```
用户点击"打开" → SAF 弹出文件选择器 → 用户选择文件
→ 通过 ContentResolver 读取文件内容
→ ViewModel.deserializeFromJson() 反序列化并替换当前画布
```

JSON 示例：
```json
[
  {
    "type": "line",
    "id": "uuid-xxx",
    "color": 4278190080,
    "strokeWidth": 4.0,
    "startX": 100.0,
    "startY": 200.0,
    "endX": 300.0,
    "endY": 400.0
  },
  {
    "type": "freehand",
    "id": "uuid-yyy",
    "color": 4294901760,
    "strokeWidth": 4.0,
    "points": [[10.0, 20.0], [15.0, 25.0], [20.0, 30.0]]
  }
]
```

## 四、文件结构说明

| 文件 | 职责 |
|------|------|
| `data/Shape.kt` | 图形数据模型（sealed class）+ 碰撞检测 + 几何工具函数 |
| `viewmodel/DrawingViewModel.kt` | 状态管理 + 撤销重做 + JSON 序列化 |
| `ui/DrawingCanvas.kt` | Canvas 绘制 + pointerInput 手势处理 |
| `ui/DrawingToolbar.kt` | 工具栏 UI（工具/颜色/粗细选择 + 操作按钮） |
| `ui/DrawingScreen.kt` | 主屏幕组合（工具栏 + 画布），连接 ViewModel |
| `MainActivity.kt` | Activity 入口 + SAF 文件选择器注册 |

## 五、扩展建议

- **更多图形**：在 `Shape.kt` 中添加新的 data class 子类，在 `DrawingCanvas.kt` 的 `when` 中添加绘制逻辑
- **填充模式**：在 `Rectangle` 中添加 `filled: Boolean` 字段，绘制时用 `Fill` 替代 `Stroke`
- **图形缩放**：在 `Shape` 中添加 `scale()` 方法，选中时显示缩放手柄
- **图层管理**：添加图层列表，支持上下移动图层顺序
- **导出图片**：将 Canvas 内容导出为 PNG（使用 `Bitmap` + `Canvas` API）
