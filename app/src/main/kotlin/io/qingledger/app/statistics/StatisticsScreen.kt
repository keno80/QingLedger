package io.qingledger.app.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.ledger.LedgerCompactRow
import io.qingledger.app.ledger.LedgerUiState
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun StatisticsScreen(state: StatisticsUiState, viewModel: StatisticsViewModel, ledger: LedgerUiState, onOpen: (String) -> Unit) {
    var chartName by rememberSaveable { mutableStateOf(StatisticsChart.RING.name) }
    val chart = StatisticsChart.valueOf(chartName)
    var rank by rememberSaveable { mutableIntStateOf(0) }
    var rankLimit by rememberSaveable(state.selection, rank) { mutableIntStateOf(50) }
    var selectedCategory by remember { mutableStateOf<StatisticsRanking?>(null) }
    val ranking = state.overview.ranking.filter { rank == 0 || it.kind == if (rank == 1) TransactionKind.INCOME else TransactionKind.EXPENSE }
    val selection = state.selection
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("统计", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (selection.period != StatisticsPeriod.ALL) IconButton(onClick = { viewModel.move(-1) }, enabled = !state.loading, modifier = Modifier.size(36.dp)) { Icon(LedgerIcons.Back, "上一时段", modifier = Modifier.size(18.dp)) }
            Text(periodTitle(selection), fontSize = 12.sp, maxLines = 1)
            if (selection.period != StatisticsPeriod.ALL) IconButton(onClick = { viewModel.move(1) }, enabled = !state.loading, modifier = Modifier.size(36.dp)) { Icon(LedgerIcons.Next, "下一时段", modifier = Modifier.size(18.dp)) }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
            LedgerSegmentedControl(StatisticsPeriod.entries.map { it.label }, selection.period.ordinal, { viewModel.period(StatisticsPeriod.entries[it]) }, enabled = !state.loading)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("收支概览", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                ChartTabs(chart) { chartName = it.name }
            }
            if (state.error != null) Column(Modifier.padding(top = 20.dp)) { Text(state.error, fontSize = 13.sp); Button(onClick = viewModel::reload) { Text("重试") } }
            else if (state.loading) Text("读取中…", Modifier.padding(vertical = 36.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            else {
                StatisticsChartContent(chart, state.overview)
                if (state.overview.summary.expense < 0) Text("退款净额超过支出，环形图仅绘制正向收支。", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        LedgerSegmentedControl(listOf("收支排行", "收入", "支出"), rank, { rank = it })
        if (ranking.isEmpty()) Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp)) {
            Text(if (state.loading) "读取中…" else if (state.error != null) "统计暂不可用" else "暂无${when (rank) { 1 -> "收入"; 2 -> "支出"; else -> "收支" }}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(horizontal = 16.dp)) {
            ranking.take(rankLimit).forEachIndexed { index, item ->
                RankingRow(item, categoryName(item, state), { selectedCategory = item })
                if (index != minOf(rankLimit, ranking.size) - 1) Box(Modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .12f)))
            }
        }
        if (ranking.size > rankLimit) Button(onClick = { rankLimit += 50 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
    }
    val selected = selectedCategory
    LedgerBottomSheet(show = selected != null, title = selected?.let { categoryName(it, state) } ?: "分类账单", onDismissRequest = { selectedCategory = null }) {
        val rows = remember(state.rows, selected) { state.rows.filter { row -> row.categoryId == selected?.categoryId && if (selected?.kind == TransactionKind.INCOME) row.kind == TransactionKind.INCOME else row.kind == TransactionKind.EXPENSE || row.kind == TransactionKind.REFUND } }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row -> LedgerCompactRow(row, ledger.copy(categories = state.categories, zoneId = state.zoneId), index == 0, index == rows.lastIndex) { selectedCategory = null; onOpen(row.id) } }
        }
    }
}

@Composable
private fun ChartTabs(selectedChart: StatisticsChart, onSelect: (StatisticsChart) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(3.dp)) {
        StatisticsChart.entries.forEach { chart ->
            val active = selectedChart == chart
            Button(onClick = { onSelect(chart) }, modifier = Modifier.size(30.dp, 24.dp).semantics { selected = active }, minWidth = 0.dp, minHeight = 24.dp, insideMargin = PaddingValues(0.dp), cornerRadius = 7.dp,
                colors = ButtonDefaults.buttonColors(color = if (active) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent)) {
                Icon(when (chart) { StatisticsChart.RING -> LedgerIcons.RingChart; StatisticsChart.LINE -> LedgerIcons.LineChart; StatisticsChart.BAR -> LedgerIcons.Statistics }, chart.label, tint = if (active) LedgerColors.accentContent else MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun RankingRow(item: StatisticsRanking, name: String, onClick: () -> Unit) {
    val income = item.kind == TransactionKind.INCOME
    val color = if (income) LedgerColors.income else LedgerColors.expense
    // Transparent miuix buttons preserve native feedback and accessibility for each category.
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), minWidth = 0.dp, minHeight = 72.dp, cornerRadius = 12.dp, insideMargin = PaddingValues(vertical = 14.dp), colors = ButtonDefaults.buttonColors(color = Color.Transparent)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .08f)), contentAlignment = Alignment.Center) { Icon(if (income) LedgerIcons.Wallet else LedgerIcons.forCategory(name), null, tint = color, modifier = Modifier.size(19.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.amountMinor < 0) "退款净额" else "${item.kind.label}占比 ${String.format(Locale.ROOT, "%.1f%%", item.share * 100)}", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) { if (item.share > 0) Box(Modifier.fillMaxWidth(item.share.toFloat()).fillMaxHeight().background(if (income) MiuixTheme.colorScheme.primary else color)) }
            }
            Text((if (income || item.amountMinor < 0) "+" else "−") + formatMoney(abs(item.amountMinor)), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (item.amountMinor < 0) LedgerColors.income else color)
        }
    }
}

private fun categoryName(item: StatisticsRanking, state: StatisticsUiState) = state.categories.find { it.id == item.categoryId }?.name ?: "未分类"
private fun periodTitle(selection: StatisticsSelection): String {
    val day = selection.anchor
    return when (selection.period) {
        StatisticsPeriod.DAY -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
        StatisticsPeriod.YEAR -> "${day.year}年"
        StatisticsPeriod.ALL -> "全部时间"
        StatisticsPeriod.WEEK -> {
            val start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); val end = start.plusDays(6)
            "${start.monthValue}月${start.dayOfMonth}日—${end.monthValue}月${end.dayOfMonth}日"
        }
    }
}
