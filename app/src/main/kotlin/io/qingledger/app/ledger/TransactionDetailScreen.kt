package io.qingledger.app.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*

@Composable
fun TransactionDetailScreen(row: LedgerTransaction, state: LedgerUiState, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onBack: () -> Unit, history: List<TransactionChange>) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.kind.label)
                AmountText(row.amountMinor)
                SettingsDescription("商户 / 标题", row.merchant.ifBlank { "未填写" })
                SettingsDescription("分类", state.categories.find { it.id == row.categoryId }?.name ?: "未分类")
                if (row.kind == TransactionKind.TRANSFER) {
                    SettingsDescription("转出账户", state.accounts.find { it.id == row.fromAccountId }?.name ?: "未指定")
                    SettingsDescription("转入账户", state.accounts.find { it.id == row.toAccountId }?.name ?: "未指定")
                } else SettingsDescription("资金账户", state.accounts.find { it.id == row.accountId }?.name ?: "未指定")
                SettingsDescription("时间", Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.of(row.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                SettingsDescription("备注", row.note.ifBlank { "未填写" })
                SettingsDescription("来源", row.sourcePackage?.let { io.qingledger.app.automationui.sourceLabel(it) + " · 自动采集" } ?: "手动记账")
            }
        }
        Button(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("编辑账单") }
        if (confirming) {
            StatusCard("确认删除？", "删除后不再计入统计，可在 10 秒内撤销。")
            Button(onClick = onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("确认删除") }
            Button(onClick = { confirming = false }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保留账单") }
        } else Button(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("删除账单") }
        Button(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("返回账单") }
        SettingsGroup("修改历史") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (history.isEmpty()) Text("暂无修改记录")
                history.forEach { change ->
                    val title = when {
                        change.operation == "CREATE" -> "新增账单"
                        change.operation == "DELETE" -> "删除账单"
                        change.operation == "RESTORE" -> "撤销删除"
                        change.operation.startsWith("EDIT:") -> "修改" + change.operation.substringAfter(':')
                        else -> "编辑账单"
                    }
                    SettingsDescription(title, Instant.ofEpochMilli(change.occurredAt).atZone(ZoneId.of(row.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                }
            }
        }
    }
}
