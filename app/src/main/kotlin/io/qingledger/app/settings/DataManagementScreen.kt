package io.qingledger.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.automationui.CaptureViewModel
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DataManagementScreen(data: DataManagementViewModel, capture: CaptureViewModel, externalBusy: Boolean, onCsvImport: () -> Unit) {
    val state by data.state.collectAsStateWithLifecycle()
    val captureMessage by capture.message.collectAsStateWithLifecycle()
    val enabled = !state.busy && !externalBusy
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    var exportingRecovery by rememberSaveable { mutableStateOf(false) }
    var showCaptureMessage by rememberSaveable { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) data.create(uri, exportingRecovery) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) data.inspect(uri) }
    fun export(recovery: Boolean) {
        showCaptureMessage = false
        exportingRecovery = recovery; panel = null
        val stamp = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        save.launch("轻账${if (recovery) "恢复前" else ""}备份-$stamp.qlbackup")
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection("备份与恢复") {
            SettingsNavigationRow("创建本地备份", "账单、分类、账户与设置", LedgerIcons.Download, { export(false) }, enabled = enabled)
            SettingsRowDivider()
            SettingsNavigationRow("恢复备份", "选择已有备份文件", LedgerIcons.Upload, { showCaptureMessage = false; panel = "restore" }, enabled = enabled)
        }
        SettingsSection("导入与导出") {
            SettingsNavigationRow("导入账单", "选择平台与文件", LedgerIcons.Upload, { panel = "import" }, enabled = enabled)
            SettingsRowDivider()
            SettingsNavigationRow("导出账单", "按范围导出 CSV", LedgerIcons.Download, { showCaptureMessage = false; panel = "csv" }, enabled = enabled)
        }
        SettingsSection("维护") {
            SettingsNavigationRow("隐藏已处理采集记录", "保留重复抑制信息", LedgerIcons.Archive, { panel = "hide" }, enabled = enabled)
            SettingsRowDivider()
            SettingsNavigationRow("数据清理", "按范围选择，操作前再次确认", LedgerIcons.Database, { info = "按范围清理暂未实现，不会删除账单。" }, enabled = enabled)
        }
        Text("数据操作集中到这里，首页不展示维护与调试按钮。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text("备份文件包含明文财务数据，请妥善保管；当前支持数据库版本 3，文件最大 16MB。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (state.busy) StatusCard("正在处理", "请等待完成后再离开。")
        (if (showCaptureMessage) captureMessage else state.message)?.let { StatusCard("数据管理提示", it) }
    }
    CsvExportSheet(show = panel == "csv", enabled = enabled, data = data, onDismiss = { panel = null })
    LedgerBottomSheet(panel != null && panel != "csv", when (panel) { "import" -> "选择导入来源"; "hide" -> "隐藏已处理记录"; else -> "恢复备份" }, { if (enabled) panel = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (panel) {
                "restore" -> {
                    Text("先选择并校验备份，确认内容后再覆盖当前数据；恢复前会自动保存当前数据，恢复后采集保持暂停。", fontSize = 14.sp)
                    Button(onClick = { panel = null; open.launch(arrayOf("*/*")) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("选择备份文件") }
                    if (state.recoveryAvailable) Button(onClick = { export(true) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("导出最近恢复前备份") }
                }
                "import" -> SettingsSection {
                    SettingsNavigationRow("微信支付", "选择导出的账单文件", LedgerIcons.Wallet, { info = "微信账单导入暂未实现。" })
                    SettingsNavigationRow("支付宝", "选择导出的账单文件", LedgerIcons.Wallet, { info = "支付宝账单导入暂未实现。" })
                    SettingsNavigationRow("轻账 CSV", "选择标准账单文件", LedgerIcons.Ledger, { panel = null; onCsvImport() })
                }
                else -> {
                    Text("只隐藏已入账、跳过或关联等已处理事件；账单与重复抑制信息仍保留。", fontSize = 14.sp)
                    Button(onClick = { showCaptureMessage = true; capture.clearMessage(); capture.clearResolved(); panel = null }, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认隐藏") }
                }
            }
            Button(onClick = { panel = null }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
    LedgerBottomSheet(state.preview != null, "确认恢复备份", { data.cancelPreview() }, allowDismiss = !state.busy) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.preview?.let { preview ->
                Text("备份时间：" + Instant.ofEpochMilli(preview.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), fontSize = 13.sp)
                Text("${preview.transactionCount}笔账单 · ${preview.accountCount}个账户 · ${preview.categoryCount}个分类", fontSize = 14.sp)
            }
            Text("将覆盖当前账本、修改记录、识别记录与设置。恢复前自动备份当前数据；系统权限不随备份恢复，自动采集保持暂停。", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = data::cancelPreview, enabled = enabled, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = data::restore, enabled = enabled, modifier = Modifier.weight(1f)) { Text(if (state.busy) "恢复中…" else "恢复并覆盖", color = LedgerColors.expense) }
            }
        }
    }
    LedgerBottomSheet(info != null, "功能说明", { info = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(info.orEmpty(), fontSize = 14.sp)
            Button(onClick = { info = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}
