package io.qingledger.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.BuildConfig
import io.qingledger.app.automationui.CaptureViewModel
import io.qingledger.designsystem.component.LedgerBottomSheet
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.domain.model.CaptureMethod
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun DeveloperToolsSheet(show: Boolean, capture: CaptureViewModel, onDismiss: () -> Unit) {
    val settings by capture.settings.collectAsStateWithLifecycle()
    val method by capture.method.collectAsStateWithLifecycle()
    val connected by capture.connected.collectAsStateWithLifecycle()
    val runtimeError by capture.runtimeError.collectAsStateWithLifecycle()
    val busy by capture.busy.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(show) { if (!show) panel = null }
    LedgerBottomSheet(show, "开发者工具", onDismiss) {
        SettingsSection {
            SettingsNavigationRow("测试器来源", "仅 Debug 版展示", LedgerIcons.Scan, { panel = "sources" })
            SettingsRowDivider()
            SettingsNavigationRow("诊断信息", "授权后查看最小诊断信息", LedgerIcons.Gear, { panel = "diagnostics" })
        }
    }
    LedgerBottomSheet(show && panel != null, when (panel) { "sources" -> "测试器来源"; "switch" -> "切换调试通道"; else -> "诊断信息" }, { if (!busy) panel = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (panel) {
                "sources" -> {
                    Text(if (BuildConfig.DEBUG) "支付测试器与 ADB 模拟来源在自动记账中按所选采集方式配置。" else "测试器来源仅在 Debug 版本开放。", fontSize = 14.sp)
                    if (BuildConfig.DEBUG) Button(onClick = { panel = "switch" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("旧通知通道回归") }
                }
                "switch" -> {
                    Text("将切换到旧通知采集通道并暂停采集。需要在自动记账中重新确认权限及来源。", fontSize = 14.sp)
                    Button(onClick = { capture.selectMethod(CaptureMethod.NOTIFICATION); panel = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("确认切换") }
                }
                else -> {
                    Text("采集方式：${method.label}", fontSize = 14.sp)
                    Text("采集：${if (settings.enabled) "已开启" else "已暂停"} · 监听：${if (connected) "已连接" else "未连接"}", fontSize = 13.sp)
                    Text("已选择 ${settings.sources.size} 个来源", fontSize = 13.sp)
                    Text(runtimeError ?: "当前无运行错误", fontSize = 13.sp)
                    Text("轻账 ${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "Debug" else "Release"}", fontSize = 12.sp)
                }
            }
            Button(onClick = { panel = null }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (panel == "switch") "取消" else "关闭") }
        }
    }
}
