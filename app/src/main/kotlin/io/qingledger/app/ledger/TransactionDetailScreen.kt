package io.qingledger.app.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import io.qingledger.designsystem.component.LedgerBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TransactionDetailScreen(row: LedgerTransaction, state: LedgerUiState, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, history: List<TransactionChange>) {
    var confirming by rememberSaveable(row.id) { mutableStateOf(false) }
    var showHistory by rememberSaveable(row.id) { mutableStateOf(false) }
    val category = state.categories.find { it.id == row.categoryId }?.name ?: "未分类"
    fun account(id: String?) = state.accounts.find { it.id == id }?.name ?: "未指定账户"
    fun time(value: Long, seconds: Boolean = false) = Instant.ofEpochMilli(value).atZone(ZoneId.of(row.zoneId))
        .format(DateTimeFormatter.ofPattern(if (seconds) "yyyy-MM-dd HH:mm:ss" else "yyyy年M月d日 HH:mm"))
    val amountColor = when (row.kind) {
        TransactionKind.EXPENSE -> LedgerColors.expense
        TransactionKind.INCOME, TransactionKind.REFUND -> LedgerColors.income
        TransactionKind.TRANSFER -> MiuixTheme.colorScheme.onSurface
    }
    val source = row.sourcePackage?.let { io.qingledger.app.automationui.sourceLabel(it) } ?: "手动记账"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(24.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(LedgerColors.accentSurface), contentAlignment = Alignment.Center) {
                    Icon(if (row.kind == TransactionKind.TRANSFER) LedgerIcons.Next else LedgerIcons.Ledger, null, tint = LedgerColors.accentContent, modifier = Modifier.size(24.dp))
                }
                Text(row.merchant.ifBlank { if (row.kind == TransactionKind.TRANSFER) "账户转账" else category }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (row.kind == TransactionKind.EXPENSE && (row.originalAmountMinor != null || row.discountMinor != null)) "实付支出" else row.kind.label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text((when (row.kind) { TransactionKind.EXPENSE -> "−"; TransactionKind.INCOME, TransactionKind.REFUND -> "+"; TransactionKind.TRANSFER -> "" }) + formatMoney(row.amountMinor), fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = amountColor)
                Text(if (row.kind == TransactionKind.REFUND) "冲减支出" else if (history.any { it.operation == "IMPORT_CSV" }) "账单导入 · $source" else if (row.sourcePackage == null) "手动记账" else "自动记账 · $source", fontSize = 12.sp, color = LedgerColors.accentContent)
            }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            row.originalAmountMinor?.let { DetailField("原价", formatMoney(it)) }
            row.discountMinor?.let { DetailField("优惠", formatMoney(it)) }
            if (row.kind != TransactionKind.TRANSFER) DetailField("分类", category)
            if (row.kind == TransactionKind.REFUND) DetailField("关联支出", if (row.originalExpenseId == null) "未关联" else state.rows.find { it.id == row.originalExpenseId }?.let { "${it.merchant.ifBlank { "支出" }} · ${formatMoney(it.amountMinor)}" } ?: "已关联原支出")
            if (row.kind == TransactionKind.TRANSFER) {
                DetailField("转出账户", account(row.fromAccountId))
                DetailField("转入账户", account(row.toAccountId))
            } else DetailField("资金账户", account(row.accountId))
            DetailField("交易时间", time(row.occurredAt))
            DetailField("记账来源", source, divider = false)
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp)) {
            Text("备注", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(10.dp))
            Text(row.note.ifBlank { "暂无备注" }, fontSize = 14.sp, color = if (row.note.isBlank()) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface)
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp), onClick = { showHistory = !showHistory }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("修改记录", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("${history.size}条 · ${if (showHistory) "收起" else "展开"}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (showHistory) {
                Spacer(Modifier.height(16.dp))
                if (history.isEmpty()) Text("暂无修改记录", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                history.sortedByDescending { it.occurredAt }.forEachIndexed { index, change ->
                    val title = when {
                        change.operation == "CREATE" -> "新增账单"
                        change.operation == "IMPORT_CSV" -> "导入账单"
                        change.operation == "DELETE" -> "删除账单"
                        change.operation == "RESTORE" -> "撤销删除"
                        change.operation.startsWith("EDIT:") -> "修改" + change.operation.substringAfter(':')
                        else -> "编辑账单"
                    }
                    Column(Modifier.padding(top = if (index == 0) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title, fontSize = 13.sp)
                        Text(time(change.occurredAt, true), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.weight(1f), minHeight = 48.dp) { Text("删除账单", color = LedgerColors.expense) }
            Button(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1.5f), minHeight = 48.dp, colors = ButtonDefaults.buttonColorsPrimary()) { Text("编辑账单") }
        }
    }
    LedgerBottomSheet(show = confirming, title = "删除账单", onDismissRequest = { if (!busy) confirming = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("确定删除这笔${row.kind.label} ${formatMoney(row.amountMinor)} 吗？删除后不再计入统计，可在 10 秒内撤销。", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirming = false }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("保留账单") }
                Button(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "正在删除…" else "确认删除", color = LedgerColors.expense) }
            }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(64.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, Modifier.weight(1f), fontSize = 14.sp)
    }
    if (divider) Box(Modifier.fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)))
}
