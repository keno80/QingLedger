package io.qingledger.app.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import io.qingledger.designsystem.component.LedgerBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.ledgerPage(state: LedgerUiState, viewModel: LedgerViewModel, onSearch: () -> Unit, onOpen: (String) -> Unit) {
    item("ledger-header") {
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("账本", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { viewModel.changeMonth(-1) }, modifier = Modifier.size(44.dp)) { Icon(LedgerIcons.Back, "上月", modifier = Modifier.size(18.dp)) }
            Text("${state.selection.month.year}年${state.selection.month.monthValue}月", fontSize = 14.sp)
            IconButton(onClick = { viewModel.changeMonth(1) }, modifier = Modifier.size(44.dp)) { Icon(LedgerIcons.Next, "下月", modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onSearch, modifier = Modifier.size(44.dp)) { Icon(LedgerIcons.Search, "搜索账单", modifier = Modifier.size(22.dp)) }
        }
    }
    item("overview") {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("本月概览", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("日常账本", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(16.dp))
            Text("支出", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(formatMoney(state.summary.expense), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = LedgerColors.expense)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("收入", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(formatMoney(state.summary.income), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = LedgerColors.income)
                }
                Column(Modifier.weight(1f)) {
                    Text("结余", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(formatMoney(state.summary.balance), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
    ledgerRows(state, viewModel, onOpen)
}

fun LazyListScope.ledgerSearchPage(state: LedgerUiState, viewModel: LedgerViewModel, onOpen: (String) -> Unit) {
    item("search") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LedgerSearchField(state, viewModel)
            Text("搜索范围：${state.selection.month.year}年${state.selection.month.monthValue}月", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    ledgerRows(state, viewModel, onOpen)
}

private fun LazyListScope.ledgerRows(state: LedgerUiState, viewModel: LedgerViewModel, onOpen: (String) -> Unit) {
    if (state.loading) item("loading") { StatusCard("正在打开账本", "请稍候。") }
    else if (state.rows.isEmpty()) item("empty") { StatusCard("暂无账单", "可调整关键词、月份或筛选条件。") }
    val zone = ZoneId.of(state.zoneId)
    val today = LocalDate.now(zone)
    val grouped = state.rows.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
    grouped.forEach { (date, rows) ->
        item("day-$date") {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val prefix = when (date) { today -> "今天 · "; today.minusDays(1) -> "昨天 · "; else -> "" }
                Text(prefix + date.format(DateTimeFormatter.ofPattern("M月d日")), Modifier.weight(1f), fontSize = 12.sp)
                val possiblyPartial = state.rows.size >= state.selection.limit && date == grouped.keys.last()
                // Summarize this result group; mark a potentially unfinished paginated day.
                FlowRow(Modifier.weight(1.8f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (possiblyPartial) "已加载" else "") + "${rows.size}笔", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    Text("收 ${formatMoney(rows.filter { it.kind == TransactionKind.INCOME }.sumOf { it.amountMinor })}", fontSize = 11.sp, color = LedgerColors.income)
                    Text("支 ${formatMoney(rows.filter { it.kind == TransactionKind.EXPENSE }.sumOf { it.amountMinor })}", fontSize = 11.sp, color = LedgerColors.expense)
                }
            }
        }
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row -> LedgerCompactRow(row, state, index == 0, index == rows.lastIndex) { onOpen(row.id) } }
    }
    if (state.rows.size >= state.selection.limit && state.selection.limit < 10000) item("load-more") { Button(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("加载更多") } }
}

@Composable
private fun LedgerSearchField(state: LedgerUiState, viewModel: LedgerViewModel) {
    var query by rememberSaveable { mutableStateOf(state.selection.query) }
    var awaitingQuery by remember { mutableStateOf(false) }
    LaunchedEffect(state.selection.query) {
        if (state.selection.query == query) awaitingQuery = false
        else if (!awaitingQuery) query = state.selection.query
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(value = query, onValueChange = { query = it; awaitingQuery = true; viewModel.search(it) }, label = "搜索商户、备注", singleLine = true,
            modifier = Modifier.weight(1f), cornerRadius = 14.dp,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            leadingIcon = { Icon(LedgerIcons.Search, null, modifier = Modifier.padding(end = 8.dp).size(18.dp)) })
        LedgerFilterButton(state, viewModel)
    }
}

@Composable
private fun LedgerFilterButton(state: LedgerUiState, viewModel: LedgerViewModel) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val filtered = state.selection.kind != null || state.selection.categoryId != null || state.selection.accountId != null
    IconButton(onClick = { showFilters = true }, modifier = Modifier.size(48.dp)) {
            Icon(LedgerIcons.Filter, if (filtered) "筛选账单，已设置" else "筛选账单", tint = if (filtered) LedgerColors.accentContent else MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
    }
    LedgerBottomSheet(show = showFilters, title = "筛选账单", onDismissRequest = { showFilters = false }) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            item { ChoiceGroup("类型", listOf(null to "全部") + TransactionKind.entries.map { it to it.label }, state.selection.kind, viewModel::filter) }
            item { ChoiceGroup("分类", listOf(null to "全部分类") + state.categories.filter { state.selection.kind == null || it.kind == state.selection.kind }.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }, state.selection.categoryId, viewModel::filterCategory) }
            item { ChoiceGroup("账户", listOf(null to "全部账户") + state.accounts.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }, state.selection.accountId, viewModel::filterAccount) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.filter(null); viewModel.filterAccount(null) }, modifier = Modifier.weight(1f)) { Text("重置") }
                    Button(onClick = { showFilters = false }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun LedgerCompactRow(row: LedgerTransaction, state: LedgerUiState, first: Boolean, last: Boolean, onOpen: () -> Unit) {
    val category = state.categories.find { it.id == row.categoryId }?.name ?: "未分类"
    fun account(id: String?) = state.accounts.find { it.id == id }?.name ?: "未指定账户"
    val accounts = if (row.kind == TransactionKind.TRANSFER) "${account(row.fromAccountId)} → ${account(row.toAccountId)}" else account(row.accountId)
    val amountColor = when (row.kind) { TransactionKind.EXPENSE -> LedgerColors.expense; TransactionKind.INCOME -> LedgerColors.income; TransactionKind.TRANSFER -> MiuixTheme.colorScheme.onSurface }
    val shape = RoundedCornerShape(topStart = if (first) 22.dp else 0.dp, topEnd = if (first) 22.dp else 0.dp, bottomStart = if (last) 22.dp else 0.dp, bottomEnd = if (last) 22.dp else 0.dp)
    Card(Modifier.fillMaxWidth().clip(shape), cornerRadius = 0.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 0.dp), onClick = onOpen) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(LedgerColors.accentSurface), contentAlignment = Alignment.Center) {
                Icon(if (row.kind == TransactionKind.TRANSFER) LedgerIcons.Next else LedgerIcons.Ledger, null, tint = LedgerColors.accentContent, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.merchant.ifBlank { if (row.kind == TransactionKind.TRANSFER) "账户转账" else category }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (row.kind == TransactionKind.TRANSFER) accounts else "$category · $accounts") + " · " + Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.of(state.zoneId)).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((when (row.kind) { TransactionKind.EXPENSE -> "−"; TransactionKind.INCOME -> "+"; TransactionKind.TRANSFER -> "" }) + formatMoney(row.amountMinor), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = amountColor)
                Text(when (row.sourcePackage) { "com.tencent.mm" -> "微信"; "com.eg.android.AlipayGphone" -> "支付宝"; "io.qingledger.paymentsimulator" -> "测试器"; null -> if (row.kind == TransactionKind.TRANSFER) "转账" else "手动"; else -> "自动记账" }, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().padding(start = 44.dp).height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)))
    }
}
