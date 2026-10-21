package io.qingledger.app.automationui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.settings.*
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.*
import java.time.*
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.captureRecords(rows: List<PendingCandidate>, capture: CaptureViewModel, zoneId: String, filter: Int, onFilter: (Int) -> Unit, onOpen: (PendingCandidate) -> Unit) {
    item("record-tabs") { LedgerSegmentedControl(listOf("全部", "已入账", "已跳过"), filter, onFilter) }
    item("capture-diagnostics") { CaptureDiagnosticsCard(capture, zoneId) }
    val zone = ZoneId.of(zoneId)
    val grouped = rows.filter { CaptureDisplayRules.matches(it.state, filter) }.sortedWith(compareByDescending<PendingCandidate> { it.receivedAt }.thenBy { it.id })
        .groupBy { Instant.ofEpochMilli(it.receivedAt).atZone(zone).toLocalDate() }
    if (grouped.isEmpty()) item("empty-records") { Column(Modifier.padding(top = 16.dp)) { SettingsSection { Text("暂无记录", Modifier.padding(20.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } } }
    grouped.forEach { (date, dayRows) ->
        item("capture-day-$date") { Text((if (date == LocalDate.now(zone)) "今天 · " else "") + date.format(DateTimeFormatter.ofPattern("M月d日")), Modifier.padding(top = 16.dp, bottom = 8.dp), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        itemsIndexed(dayRows, key = { _, row -> "capture-${row.id}" }) { index, row ->
            val shape = RoundedCornerShape(topStart = if (index == 0) 22.dp else 0.dp, topEnd = if (index == 0) 22.dp else 0.dp, bottomStart = if (index == dayRows.lastIndex) 22.dp else 0.dp, bottomEnd = if (index == dayRows.lastIndex) 22.dp else 0.dp)
            Card(Modifier.fillMaxWidth().clip(shape), cornerRadius = 0.dp, insideMargin = PaddingValues(0.dp)) {
                SettingsNavigationRow((row.merchant?.takeIf { it.isNotBlank() } ?: sourceLabel(row.sourcePackage)) + (row.amountMinor?.let { " · ${formatMoney(it)}" } ?: ""),
                    (if (row.merchant.isNullOrBlank()) "" else sourceLabel(row.sourcePackage) + " · ") + Instant.ofEpochMilli(row.receivedAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) + (row.accountName?.let { " · $it" } ?: ""),
                    when (row.state) { "MERGED" -> LedgerIcons.Automation; "SKIPPED", "IGNORED" -> LedgerIcons.Privacy; else -> LedgerIcons.Ledger }, { onOpen(row) }) {
                    CaptureBadge(CaptureDisplayRules.stateLabel(row.state), row.state in setOf("AUTO_POSTED", "CONFIRMED"))
                }
                if (index != dayRows.lastIndex) SettingsRowDivider()
            }
        }
    }
    item("capture-record-note") { Text("先展示结果，点开查看原因。最多展示最近 200 条未隐藏记录。", Modifier.padding(top = 12.dp), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
}

@Composable
private fun CaptureDiagnosticsCard(capture: CaptureViewModel, zoneId: String) {
    ObserveCaptureAccess(capture)
    val rows by capture.diagnostics.collectAsStateWithLifecycle()
    val settings by capture.settings.collectAsStateWithLifecycle()
    val method by capture.method.collectAsStateWithLifecycle()
    val access by capture.access.collectAsStateWithLifecycle()
    val connected by capture.connected.collectAsStateWithLifecycle()
    var show by remember { mutableStateOf(false) }
    val status = CaptureDisplayRules.status(method, settings.enabled, access, connected, settings.sources.size)
    val latest = rows.lastOrNull()
    Column(Modifier.padding(top = 14.dp)) {
        SettingsSection {
            SettingsNavigationRow("采集诊断", latest?.let { sourceLabel(it.sourcePackage) + " · " + CaptureDiagnosticLabels.reason(it.reason) } ?: "${method.label} · $status · 暂无事件", LedgerIcons.Scan, { show = true })
        }
    }
    LedgerBottomSheet(show, "采集诊断", { show = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${method.label} · $status", fontSize = 13.sp)
            if (rows.isEmpty()) Text("尚未收到来源事件。请在已开启的来源中完成操作后返回查看。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            else Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.asReversed().forEach { item ->
                    SettingsSection {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(sourceLabel(item.sourcePackage) + " · " + Instant.ofEpochMilli(item.at).atZone(ZoneId.of(zoneId)).format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " · " + CaptureDiagnosticLabels.stage(item.stage), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(CaptureDiagnosticLabels.reason(item.reason) + (item.fields?.let { "（$it 项文字）" } ?: ""), fontSize = 13.sp)
                            item.details?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                        }
                    }
                }
            }
            Text("仅保留本次运行的最近 40 条状态，不保存页面原文。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
fun CaptureRecordSheet(row: PendingCandidate?, zoneId: String, onClose: () -> Unit, onTransaction: (String) -> Unit, onLegacy: (String) -> Unit) {
    LedgerBottomSheet(row != null, "识别记录", onClose) {
        if (row != null) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsSection {
                SettingsNavigationRow("识别结果", null, LedgerIcons.Scan, {}) { CaptureBadge(CaptureDisplayRules.stateLabel(row.state), row.state in setOf("AUTO_POSTED", "CONFIRMED")) }
                SettingsRowDivider()
                SettingsNavigationRow("来源与时间", sourceLabel(row.sourcePackage) + " · " + Instant.ofEpochMilli(row.receivedAt).atZone(ZoneId.of(zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), LedgerIcons.Archive, {}) { }
            }
            Text(when (row.state) { "AUTO_POSTED" -> "已直接入账，可在账单中编辑或删除。"; "CONFIRMED" -> "已人工入账。"; "MERGED" -> "已关联原账单，未重复入账。"; "IGNORED" -> "已忽略，未生成新账单。"; else -> reviewLabel(row.reason) }, fontSize = 13.sp)
            val transaction = row.transactionId ?: if (row.state == "MERGED") row.relatedTransactionId else null
            if (transaction != null) Button(onClick = { onClose(); onTransaction(transaction) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("查看账单") }
            if (row.state == "PENDING") Button(onClick = { onClose(); onLegacy(row.id) }, modifier = Modifier.fillMaxWidth()) { Text("查看旧版事件") }
        }
    }
}
