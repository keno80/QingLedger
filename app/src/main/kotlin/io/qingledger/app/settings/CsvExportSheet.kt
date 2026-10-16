package io.qingledger.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.LedgerBottomSheet
import io.qingledger.designsystem.component.LedgerSegmentedControl
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.export.CsvExportRequest
import io.qingledger.domain.export.CsvExportScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CsvExportSheet(show: Boolean, enabled: Boolean, data: DataManagementViewModel, onDismiss: () -> Unit) {
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep the exact request across activity recreation while the system picker is open.
    var pendingScope by rememberSaveable { mutableIntStateOf(0) }
    var pendingStart by rememberSaveable { mutableStateOf("") }
    var pendingEnd by rememberSaveable { mutableStateOf("") }
    var pendingTime by rememberSaveable { mutableLongStateOf(0L) }
    val focus = LocalFocusManager.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) data.exportCsv(uri, CsvExportRequest(CsvExportScope.entries[pendingScope], pendingStart, pendingEnd, pendingTime))
    }
    LedgerBottomSheet(show, "导出账单", { if (enabled) onDismiss() }, allowDismiss = enabled) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LedgerSegmentedControl(listOf("本月", "全部", "自定义"), scope, { scope = it; error = null }, enabled = enabled)
            if (scope == CsvExportScope.CUSTOM.ordinal) {
                TextField(start, { start = it; error = null }, label = "开始日期（yyyy-MM-dd）", singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                TextField(end, { end = it; error = null }, label = "结束日期（yyyy-MM-dd）", singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
            Text("按账本时区筛选，包含起止日期；导出收入、支出与转账，不含已删除账单。单次最多 50000 笔。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("CSV 用于查看与分析，包含明文财务数据；完整恢复请使用本地备份。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            error?.let { Text(it, fontSize = 13.sp, color = LedgerColors.expense) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { focus.clearFocus(); onDismiss() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = {
                    val request = CsvExportRequest(CsvExportScope.entries[scope], start, end)
                    try { request.period(ZoneId.of("UTC")) }
                    catch (failure: IllegalArgumentException) { error = failure.message; return@Button }
                    pendingScope = scope; pendingStart = start; pendingEnd = end; pendingTime = request.requestedAt
                    focus.clearFocus(); onDismiss()
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    save.launch("轻账账单-$stamp.csv")
                }, enabled = enabled, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("选择保存位置") }
            }
        }
    }
}
