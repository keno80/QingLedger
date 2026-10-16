package io.qingledger.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.automationui.CaptureViewModel
import io.qingledger.designsystem.component.LedgerBottomSheet
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.theme.LedgerColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(capture: CaptureViewModel, onAutomation: () -> Unit, onAppearance: () -> Unit) {
    val settings by capture.settings.collectAsStateWithLifecycle()
    val method by capture.method.collectAsStateWithLifecycle()
    var privacy by rememberSaveable { mutableStateOf(false) }
    var developer by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection("记账设置") {
            SettingsNavigationRow("自动记账", if (settings.enabled) "已开启 · ${method.label}" else "已暂停", LedgerIcons.Scan, onAutomation) {
                Text(if (settings.enabled) "已开启" else "已暂停", modifier = Modifier.background(if (settings.enabled) LedgerColors.accentSurface else MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, color = if (settings.enabled) LedgerColors.accentContent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        SettingsSection("显示与隐私") {
            SettingsNavigationRow("显示与主题", "薄荷绿 · 紧凑列表", LedgerIcons.Palette, onAppearance)
            SettingsRowDivider()
            SettingsNavigationRow("隐私与安全", "本地存储，按需授权", LedgerIcons.Privacy, { privacy = true })
        }
        SettingsSection("高级") {
            SettingsNavigationRow("开发者工具", "调试来源与诊断信息", LedgerIcons.Gear, { developer = true })
        }
    }
    LedgerBottomSheet(privacy, "隐私与安全", { privacy = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("账单保存在本机，无需注册账号。", fontSize = 14.sp)
            Text("自动记账需要主动授权并开启所选来源；只读取所选应用的交易信息，不保存页面或通知原文，不执行点击或付款操作。", fontSize = 14.sp)
            Text("可在自动记账页面暂停采集、关闭来源，或在系统设置中撤销权限。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Button(onClick = { privacy = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
    DeveloperToolsSheet(developer, capture) { developer = false }
}
