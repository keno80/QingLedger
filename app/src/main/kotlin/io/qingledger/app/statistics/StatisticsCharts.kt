package io.qingledger.app.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.formatMoney
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.util.Locale
import kotlin.math.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class StatisticsChart(val label: String) { RING("环形图"), LINE("折线图"), BAR("柱状图") }

@Composable
internal fun StatisticsChartContent(chart: StatisticsChart, overview: StatisticsOverview) {
    val summary = overview.summary
    if (chart == StatisticsChart.RING) Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val incomeColor = MiuixTheme.colorScheme.primary
        val expenseColor = LedgerColors.expense
        val track = MiuixTheme.colorScheme.secondaryContainer
        Box(Modifier.size(136.dp).semantics { contentDescription = "收入 ${formatMoney(summary.income)}，支出 ${formatMoney(summary.expense)}，结余 ${formatMoney(summary.balance)}" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 16.dp.toPx(); val origin = Offset(stroke / 2, stroke / 2); val ringSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, -90f, 360f, false, origin, ringSize, style = Stroke(stroke))
                val positiveExpense = summary.expense.coerceAtLeast(0).toDouble()
                val total = summary.income.toDouble() + positiveExpense
                if (total > 0) {
                    val sweep = (summary.income.toDouble() / total * 360).toFloat()
                    if (sweep > 0) drawArc(incomeColor, -90f, sweep, false, origin, ringSize, style = Stroke(stroke))
                    if (sweep < 360) drawArc(expenseColor, -90f + sweep, 360f - sweep, false, origin, ringSize, style = Stroke(stroke))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("结余", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(formatMoney(summary.balance), fontSize = if (formatMoney(summary.balance).length > 12) 11.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ChartMetric("收入", summary.income, true)
            ChartMetric("支出", summary.expense, false)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { ChartMetric("收入", summary.income, true, true) }
            Box(Modifier.weight(1f)) { ChartMetric("支出", summary.expense, false, true) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("结余", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(formatMoney(summary.balance), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        TrendChart(overview.trend, chart)
    }
}

@Composable
private fun ChartMetric(label: String, amount: Long, income: Boolean, compact: Boolean = false) {
    val color = if (income || amount < 0) LedgerColors.income else LedgerColors.expense
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(if (income) MiuixTheme.colorScheme.primary else LedgerColors.expense))
            Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(formatMoney(amount), fontSize = if (compact) 12.sp else 16.sp, color = color, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun TrendChart(buckets: List<StatisticsBucket>, chart: StatisticsChart) {
    val incomeColor = MiuixTheme.colorScheme.primary
    val expenseColor = LedgerColors.expense
    val labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val gridColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .18f)
    val measurer = rememberTextMeasurer()
    val description = remember(buckets, chart) { chart.label + "，" + buckets.joinToString("；") { "${it.label} 收入${formatMoney(it.income)} 支出${formatMoney(it.expense)}" } }
    Canvas(Modifier.fillMaxWidth().height(184.dp).semantics { contentDescription = description }) {
        val left = 38.dp.toPx(); val right = size.width - 8.dp.toPx(); val top = 12.dp.toPx(); val bottom = size.height - 30.dp.toPx()
        val values = buckets.flatMap { listOf(it.income.toDouble(), it.expense.toDouble()) }
        fun nice(value: Double): Double { if (value <= 0) return 0.0; val power = 10.0.pow(floor(log10(value))); return ceil(value / power) * power }
        val upper = nice(values.maxOrNull()?.coerceAtLeast(100.0) ?: 100.0)
        val minimum = values.minOrNull() ?: 0.0
        val lower = if (minimum < 0) -nice(-minimum) else 0.0
        fun y(value: Double) = (bottom - (bottom - top) * (value - lower) / (upper - lower)).toFloat()
        fun x(index: Int) = left + (right - left) * (index + .5f) / buckets.size.coerceAtLeast(1)
        val textStyle = TextStyle(color = labelColor, fontSize = 9.sp)
        fun label(value: String, centerX: Float, topY: Float, rightAlign: Boolean = false) {
            val layout = measurer.measure(value, textStyle)
            drawText(layout, topLeft = Offset(if (rightAlign) centerX - layout.size.width else centerX - layout.size.width / 2f, topY))
        }
        val ticks = listOf(lower, (lower + upper) / 2, upper, 0.0).distinct().sorted()
        ticks.forEach { value ->
            drawLine(gridColor, Offset(left, y(value)), Offset(right, y(value)), strokeWidth = 1.dp.toPx(), pathEffect = if (value == 0.0) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
            label(axisAmount(value), left - 5.dp.toPx(), y(value) - 5.dp.toPx(), true)
        }
        val labelIndices = when {
            buckets.size <= 7 -> buckets.indices.toSet()
            buckets.size <= 12 -> buckets.indices.filter { it % 2 == 0 || it == buckets.lastIndex }.toSet()
            else -> (0..6).map { (it * (buckets.size - 1) / 6.0).roundToInt() }.toSet()
        }
        buckets.forEachIndexed { index, bucket -> if (index in labelIndices) label(bucket.label, x(index), bottom + 10.dp.toPx()) }
        listOf(true, false).forEach { income ->
            val color = if (income) incomeColor else expenseColor
            val series = buckets.map { if (income) it.income.toDouble() else it.expense.toDouble() }
            if (chart == StatisticsChart.LINE) {
                val path = Path()
                series.forEachIndexed { i, value -> if (i == 0) path.moveTo(x(i), y(value)) else path.lineTo(x(i), y(value)) }
                drawPath(path, color, style = Stroke(2.dp.toPx()))
                series.forEachIndexed { i, value -> drawCircle(color, 2.5.dp.toPx(), Offset(x(i), y(value))) }
            } else {
                val width = min(13.dp.toPx(), (right - left) / buckets.size.coerceAtLeast(1) * .28f)
                series.forEachIndexed { i, value ->
                    if (value != 0.0) drawRoundRect(color, topLeft = Offset(x(i) + if (income) -width - 1.dp.toPx() else 1.dp.toPx(), min(y(value), y(0.0))), size = Size(width, abs(y(value) - y(0.0))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
        }
    }
}

private fun axisAmount(minor: Double): String {
    val major = minor / 100
    return if (abs(major) >= 10000) String.format(Locale.ROOT, "%.1f万", major / 10000) else String.format(Locale.ROOT, if (major % 1 == 0.0) "%.0f" else "%.1f", major)
}
