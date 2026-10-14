package io.qingledger.app.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.ledgerPage(state: LedgerUiState, viewModel: LedgerViewModel, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    item("overview") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            var showFilters by rememberSaveable { mutableStateOf(false) }
            MonthSelector(state, viewModel)
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("本月净支出", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
                    AmountText(state.summary.expense)
                    Text("收入 ${formatMoney(state.summary.income)}", fontSize = 14.sp)
                    Text("结余 ${formatMoney(state.summary.balance)}", fontSize = 14.sp)
                }
            }
            Button(onClick = onAdd, enabled = !state.loading, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp, colors = ButtonDefaults.buttonColorsPrimary()) { Text("记一笔") }
            TextField(value = state.selection.query, onValueChange = viewModel::search, label = "搜索商户或备注", singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { showFilters = !showFilters }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showFilters) "收起筛选" else if (state.selection.kind != null || state.selection.categoryId != null || state.selection.accountId != null) "筛选（已设置）" else "筛选账单")
            }
            if (showFilters) {
                ChoiceGroup("类型", listOf(null to "全部") + TransactionKind.entries.map { it to it.label }, state.selection.kind, viewModel::filter)
                ChoiceGroup("分类", listOf(null to "全部分类") + state.categories.filter { state.selection.kind == null || it.kind == state.selection.kind }.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }, state.selection.categoryId, viewModel::filterCategory)
                ChoiceGroup("账户", listOf(null to "全部账户") + state.accounts.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }, state.selection.accountId, viewModel::filterAccount)
            }
            if (state.loading) StatusCard("正在打开账本", "请稍候。")
            else if (state.rows.isEmpty()) StatusCard("暂无账单", "点击记一笔，或者调整月份和筛选条件。")
        }
    }
    val zone = ZoneId.of(state.zoneId)
    state.rows.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }.forEach { (date, rows) ->
        item("day-$date") { Text(date.format(DateTimeFormatter.ofPattern("M 月 d 日")), fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant) }
        items(rows, key = { it.id }) { row ->
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp), onClick = { onOpen(row.id) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.merchant.ifBlank { state.categories.find { it.id == row.categoryId }?.name ?: row.kind.label }, fontWeight = FontWeight.SemiBold)
                    Text((when (row.kind) { TransactionKind.INCOME -> "+"; TransactionKind.EXPENSE -> "−"; TransactionKind.TRANSFER -> "转账 " }) + formatMoney(row.amountMinor), fontSize = 22.sp)
                    Text(if (row.kind == TransactionKind.TRANSFER) "${state.accounts.find { it.id == row.fromAccountId }?.name} → ${state.accounts.find { it.id == row.toAccountId }?.name}" else "${row.kind.label} · ${state.categories.find { it.id == row.categoryId }?.name ?: "未分类"} · ${state.accounts.find { it.id == row.accountId }?.name ?: "未指定账户"}", fontSize = 13.sp)
                    if (row.note.isNotBlank()) Text(row.note, fontSize = 13.sp)
                }
            }
        }
    }
    if (state.rows.size >= state.selection.limit && state.selection.limit < 10000) item("load-more") { Button(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
}
