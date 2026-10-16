package io.qingledger.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import io.qingledger.designsystem.theme.LedgerColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Month calendar composed entirely from miuix controls and app theme tokens. */
@Composable
fun LedgerDatePicker(value: LocalDate, onValueChange: (LocalDate) -> Unit, enabled: Boolean = true) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(value).toString()) }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    LaunchedEffect(value) { monthText = YearMonth.from(value).toString() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthText = month.minusMonths(1).toString() }, enabled = enabled && month != YearMonth.of(1, 1), modifier = Modifier.size(44.dp)) {
                Icon(LedgerIcons.Back, "上个月", modifier = Modifier.size(18.dp))
            }
            Button(onClick = { choosingMonth = !choosingMonth }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("${month.year}年${month.monthValue}月", fontSize = 16.sp) }
            IconButton(onClick = { monthText = month.plusMonths(1).toString() }, enabled = enabled && month != YearMonth.of(9999, 12), modifier = Modifier.size(44.dp)) {
                Icon(LedgerIcons.Next, "下个月", modifier = Modifier.size(18.dp))
            }
        }
        if (choosingMonth) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberPicker(month.year, { monthText = month.withYear(it).toString() }, range = 1..9999, label = { "${it}年" }, enabled = enabled, visibleItemCount = 3, textStyle = TextStyle(fontSize = 22.sp), modifier = Modifier.weight(1f))
                NumberPicker(month.monthValue, { monthText = month.withMonth(it).toString() }, range = 1..12, label = { "${it}月" }, enabled = enabled, visibleItemCount = 3, textStyle = TextStyle(fontSize = 22.sp), modifier = Modifier.weight(1f))
            }
            Button(onClick = { choosingMonth = false }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("返回日历") }
        } else {
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val weeks = (offset + month.lengthOfMonth() + 6) / 7
            repeat(weeks) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(7) { weekday ->
                        val day = week * 7 + weekday - offset + 1
                        if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(42.dp))
                        else {
                            val date = month.atDay(day)
                            val active = date == value
                            IconButton(onClick = { onValueChange(date) }, enabled = enabled, cornerRadius = 14.dp,
                                backgroundColor = if (active) LedgerColors.accentSurface else Color.Transparent,
                                modifier = Modifier.weight(1f).height(42.dp).semantics { selected = active; contentDescription = date.toString() }) {
                                Text(day.toString(), fontSize = 15.sp, color = if (active) LedgerColors.accentContent else MiuixTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
        Text("已选：$value", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
fun LedgerTimePicker(value: LocalTime, onValueChange: (LocalTime) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberPicker(value.hour, { onValueChange(value.withHour(it)) }, range = 0..23,
            label = { "${it.toString().padStart(2, '0')}时" }, enabled = enabled, visibleItemCount = 3, modifier = Modifier.weight(1f))
        NumberPicker(value.minute, { onValueChange(value.withMinute(it)) }, range = 0..59,
            label = { "${it.toString().padStart(2, '0')}分" }, enabled = enabled, visibleItemCount = 3, modifier = Modifier.weight(1f))
    }
}
