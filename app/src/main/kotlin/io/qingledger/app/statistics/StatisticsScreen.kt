package io.qingledger.app.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.ledger.*
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val chartColors = listOf(Color(0xFF73D6BB), Color(0xFF86C3A8), Color(0xFFB5A1D2), Color(0xFFEDA399), Color(0xFFBBBFCA))

@Composable
fun StatisticsScreen(state: LedgerUiState, viewModel: LedgerViewModel, onOpen: (String) -> Unit) {
    var calendar by rememberSaveable { mutableStateOf(false) }
    var showScope by rememberSaveable { mutableStateOf(false) }
    val month = state.selection.month
    val today = LocalDate.now(ZoneId.of(state.zoneId))
    var selectedDay by rememberSaveable(month.toString()) { mutableIntStateOf(if (today.year == month.year && today.month == month.month) today.dayOfMonth else 1) }
    var dayLimit by rememberSaveable(month.toString(), selectedDay) { mutableIntStateOf(50) }
    val days = remember(state.monthlyRows, state.zoneId) { StatisticsRules.days(state.monthlyRows, ZoneId.of(state.zoneId)) }
    val categories = remember(state.monthlyRows) { StatisticsRules.categories(state.monthlyRows) }
    val selectedRows = days[month.atDay(selectedDay)].orEmpty()
    val summary = LedgerRules.summarize(state.monthlyRows)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("统计", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { viewModel.changeMonth(-1) }, modifier = Modifier.size(44.dp)) { Icon(LedgerIcons.Back, "上月", modifier = Modifier.size(18.dp)) }
            Text("${month.year}年${month.monthValue}月", fontSize = 14.sp)
            IconButton(onClick = { viewModel.changeMonth(1) }, modifier = Modifier.size(44.dp)) { Icon(LedgerIcons.Next, "下月", modifier = Modifier.size(18.dp)) }
            IconButton(onClick = { showScope = true }, modifier = Modifier.size(36.dp)) { Text("⋯", fontSize = 24.sp) }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
            LedgerSegmentedControl(listOf("分类", "日历"), if (calendar) 1 else 0, { calendar = it == 1 })
            Spacer(Modifier.height(18.dp))
            if (calendar) {
                Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f).wrapContentWidth(), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
                val offset = month.atDay(1).dayOfWeek.value - 1
                repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                        repeat(7) { column ->
                            val day = week * 7 + column - offset + 1
                            if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp))
                            else {
                                val daily = LedgerRules.summarize(days[month.atDay(day)].orEmpty())
                                Button(onClick = { selectedDay = day }, modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = month.atDay(day).toString() }, minWidth = 0.dp, minHeight = 48.dp, insideMargin = PaddingValues(0.dp), cornerRadius = 10.dp,
                                    colors = ButtonDefaults.buttonColors(color = if (day == selectedDay) LedgerColors.accentSurface else Color.Transparent)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("$day", fontSize = 12.sp, color = if (day == selectedDay) LedgerColors.accentContent else MiuixTheme.colorScheme.onSurface)
                                        if (daily.expense != 0L) Text((if (daily.expense > 0) "−" else "+") + formatMoney(kotlin.math.abs(daily.expense)).removePrefix("¥"), fontSize = 8.sp, color = if (daily.expense > 0) LedgerColors.expense else LedgerColors.income, maxLines = 1)
                                        if (daily.income != 0L) Text("+" + formatMoney(daily.income).removePrefix("¥"), fontSize = 8.sp, color = LedgerColors.income, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("支出分类", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("月", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    val track = MiuixTheme.colorScheme.secondaryContainer
                    Box(Modifier.size(136.dp).semantics { contentDescription = "本月支出 ${formatMoney(summary.expense)}" }, contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val width = 16.dp.toPx()
                            val diameter = size.width - width
                            val origin = Offset(width / 2, width / 2)
                            drawArc(track, -90f, 360f, false, origin, Size(diameter, diameter), style = Stroke(width))
                            var angle = -90f
                            categories.forEachIndexed { index, category ->
                                val sweep = (category.share * 360).toFloat()
                                if (sweep > 0) drawArc(chartColors[index % chartColors.size], angle, sweep, false, origin, Size(diameter, diameter), style = Stroke(width))
                                angle += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("本月支出", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(formatMoney(summary.expense), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        categories.forEachIndexed { index, category ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(chartColors[index % chartColors.size]))
                                Text(categoryName(category, state), Modifier.weight(1f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(percentage(category), fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        if (categories.isEmpty()) Text(if (state.loading) "读取中…" else "暂无支出", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (categories.any { it.amountMinor < 0 }) Text("占比按正向净支出计算，退款净额列于排行。", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (calendar) {
            val daily = LedgerRules.summarize(selectedRows)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${month.monthValue}月${selectedDay}日", Modifier.weight(1f), fontSize = 12.sp)
                Text("收 ${formatMoney(daily.income)}  支 ${formatMoney(daily.expense)}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (selectedRows.isEmpty()) Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp)) { Text("当天暂无账单", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            else Column { selectedRows.take(dayLimit).forEachIndexed { index, row -> LedgerCompactRow(row, state, index == 0, index == minOf(dayLimit, selectedRows.size) - 1) { onOpen(row.id) } } }
            if (dayLimit < selectedRows.size) Button(onClick = { dayLimit += 50 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
        } else {
            Text("分类排行", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(horizontal = 16.dp)) {
                if (categories.isEmpty()) Text("暂无支出", Modifier.padding(vertical = 20.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                categories.forEachIndexed { index, category ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(chartColors[index % chartColors.size].copy(alpha = .16f)), contentAlignment = Alignment.Center) { Icon(LedgerIcons.forCategory(categoryName(category, state)), null, modifier = Modifier.size(19.dp), tint = LedgerColors.accentContent) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row { Text(categoryName(category, state), Modifier.weight(1f), fontSize = 13.sp); Text(percentage(category), fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
                                if (category.share > 0) Box(Modifier.fillMaxWidth(category.share.toFloat()).fillMaxHeight().background(chartColors[index % chartColors.size]))
                            }
                        }
                        Text(formatMoney(category.amountMinor), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    if (index != categories.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .12f)))
                }
            }
        }
    }
    LedgerBottomSheet(show = showScope, title = "统计口径", onDismissRequest = { showScope = false }) {
        Text("按账本时区统计整月账单，转账不计入收支；退款按到账日期冲减对应分类支出。", fontSize = 14.sp)
    }
}

private fun categoryName(category: CategoryStatistic, state: LedgerUiState) = state.categories.find { it.id == category.categoryId }?.name ?: "未分类"
private fun percentage(category: CategoryStatistic) = if (category.amountMinor < 0) "退款净额" else String.format(Locale.ROOT, "%.1f%%", category.share * 100)
