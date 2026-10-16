package io.qingledger.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.LedgerBottomSheet
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.component.LedgerSegmentedControl
import io.qingledger.designsystem.component.StatusCard
import io.qingledger.domain.model.ThemeMode
import io.qingledger.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppearanceScreen(themeMode: ThemeMode, settingsRepository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
    val labels = listOf("跟随系统", "浅色", "深色")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection("外观") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("主题", fontSize = 14.sp)
                    Text(labels[modes.indexOf(themeMode)], fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                LedgerSegmentedControl(labels, modes.indexOf(themeMode), { index ->
                    saving = true; error = null
                    scope.launch {
                        try { settingsRepository.setThemeMode(modes[index]) }
                        catch (_: IOException) { error = "主题保存失败，请重试。" }
                        finally { saving = false }
                    }
                }, enabled = !saving)
            }
        }
        SettingsSection {
            SettingsNavigationRow("主题色", "薄荷绿", LedgerIcons.Palette, { info = "当前使用薄荷绿，暂不支持自定义主题色。" }) {
                Box(Modifier.size(19.dp).background(MiuixTheme.colorScheme.primary, CircleShape))
            }
            SettingsRowDivider()
            SettingsNavigationRow("账单显示", "紧凑 · 商户 / 账户 / 来源", LedgerIcons.Filter, { info = "当前使用紧凑账单布局，自定义显示暂未开放。" })
        }
        SettingsSection("显示设置") {
            FixedDisplayRow("显示账户", "在账单副标题中显示资金账户") { info = "当前账单已显示资金账户，暂不支持单独隐藏。" }
            SettingsRowDivider()
            FixedDisplayRow("显示来源标签", "区分手动记账与自动采集") { info = "当前账单已显示来源标签，暂不支持单独隐藏。" }
            SettingsRowDivider()
            FixedDisplayRow("紧凑列表", null) { info = "当前使用紧凑列表，暂不支持切换其他密度。" }
        }
        Text("主题切换已支持，其他显示选项暂未开放调整。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        error?.let { StatusCard("未能保存设置", it) }
    }
    LedgerBottomSheet(info != null, "显示说明", { info = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(info.orEmpty(), fontSize = 14.sp)
            Button(onClick = { info = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}

@Composable
private fun FixedDisplayRow(title: String, summary: String?, onClick: () -> Unit) {
    SettingsNavigationRow(title, summary, null, onClick) {
        Switch(checked = true, onCheckedChange = null, enabled = false,
            colors = SwitchDefaults.switchColors(disabledCheckedTrackColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.35f), disabledCheckedThumbColor = MiuixTheme.colorScheme.surfaceContainer))
    }
}
