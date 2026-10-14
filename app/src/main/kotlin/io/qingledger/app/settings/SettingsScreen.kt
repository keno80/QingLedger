package io.qingledger.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.qingledger.designsystem.component.SettingsDescription
import io.qingledger.designsystem.component.SettingsGroup
import io.qingledger.designsystem.component.StatusCard
import io.qingledger.domain.model.ThemeMode
import io.qingledger.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun SettingsScreen(themeMode: ThemeMode, settingsRepository: SettingsRepository, onCategories: () -> Unit, onAccounts: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup("账本管理") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCategories, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("分类管理") }
                Button(onClick = onAccounts, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("账户管理") }
            }
        }
        SettingsGroup("外观") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val selected = themeMode == mode
                    Button(
                        modifier = Modifier.fillMaxWidth().semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        },
                        minHeight = 48.dp,
                        enabled = !saving,
                        colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    settingsRepository.setThemeMode(mode)
                                } catch (_: IOException) {
                                    error = "主题保存失败，请重试。"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) {
                        Text(when (mode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        })
                    }
                }
            }
        }
        error?.let { StatusCard("未能保存设置", it) }
        SettingsGroup("隐私") {
            SettingsDescription("本地优先", "无需账号。账单保存在本机，不联网。通知采集需单独授权并主动选择来源。")
        }
        SettingsGroup("关于") {
            SettingsDescription("轻账", "0.5.3 · 无障碍记账预览版")
        }
    }
}
