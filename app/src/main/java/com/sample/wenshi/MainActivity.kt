package com.sample.wenshi

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.sample.wenshi.ui.DrawingScreen
import com.sample.wenshi.viewmodel.DrawingViewModel
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 应用主入口 Activity。
 *
 * 职责：
 * 1. 设置全屏沉浸式显示（enableEdgeToEdge）
 * 2. 注册 SAF（Storage Access Framework）文件选择器
 * 3. 将 DrawingScreen 设置为内容视图
 *
 * 关于 SAF（Storage Access Framework）：
 * - Android 推荐的文件访问方式，不需要申请存储权限
 * - 系统会弹出标准的文件选择/保存对话框
 * - 通过 ActivityResultContracts 注册回调，替代传统的 startActivityForResult
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启用边到边显示（状态栏和导航栏透明，内容延伸到屏幕边缘）
        enableEdgeToEdge()
        setContent {
            // setContent 是 Compose 的入口点，将 @Composable 函数设置为 Activity 的内容视图
            // 替代传统 XML 布局的 setContentView(R.layout.activity_main)
            DrawingApp()
        }
    }
}

/**
 * 应用根 Composable —— 注册文件选择器并连接到 DrawingScreen。
 *
 * Compose 要点：
 * - rememberLauncherForActivityResult 注册一个 Activity Result 回调
 *   它就像传统的 onActivityResult，但类型安全且自动管理生命周期
 * - remember 在重组间保持值不变（类似 ViewModel，但作用域是 Composable 函数）
 */
@Composable
fun DrawingApp() {
    val context = LocalContext.current
    val viewModel: DrawingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    // ===== 保存文件启动器 =====
    // ActivityResultContracts.CreateDocument("application/json") 打开系统保存文件对话框
    // 用户选择保存位置后，onActivityResult 回调中拿到文件的 Uri
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            // 将 ViewModel 中的图形序列化为 JSON
            val json = viewModel.serializeToJson()
            // 通过 ContentResolver 写入用户选择的文件
            context.contentResolver.openOutputStream(it)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }
        }
    }

    // ===== 打开文件启动器 =====
    // ActivityResultContracts.OpenDocument 打开系统文件选择对话框
    // 参数 arrayOf("application/json") 限制只显示 JSON 文件
    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // 从用户选择的文件中读取 JSON 内容
            val json = context.contentResolver.openInputStream(it)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
            // 反序列化并加载到 ViewModel；格式错误时 Toast 提示而不崩溃
            if (json != null) {
                val ok = viewModel.deserializeFromJson(json)
                if (!ok) {
                    Toast.makeText(context, "文件格式错误", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 渲染主屏幕，传入保存和打开的回调
    DrawingScreen(
        viewModel = viewModel,
        onSave = {
            // 点击保存时，启动系统保存对话框
            // "drawing.json" 是默认文件名，用户可以修改
            saveLauncher.launch("drawing.json")
        },
        onOpen = {
            // 点击打开时，启动系统文件选择对话框
            openLauncher.launch(arrayOf("application/json"))
        }
    )
}
