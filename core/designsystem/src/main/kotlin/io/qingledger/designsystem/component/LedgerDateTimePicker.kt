package io.qingledger.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import top.yukonga.miuix.kmp.basic.NumberPicker

/** Uses the miuix wheel's own gestures, snapping, haptics and theme colors. */
@Composable
fun LedgerDatePicker(value: LocalDate, onValueChange: (LocalDate) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberPicker(value.year, { onValueChange(value.withYear(it)) }, range = 1..9999,
            label = { "${it}年" }, enabled = enabled, visibleItemCount = 3, modifier = Modifier.weight(1.2f))
        NumberPicker(value.monthValue, { onValueChange(value.withMonth(it)) }, range = 1..12,
            label = { "${it}月" }, enabled = enabled, visibleItemCount = 3, modifier = Modifier.weight(1f))
        // Recreate the day wheel when its range changes, discarding any old fling.
        key(value.year, value.monthValue) {
            NumberPicker(value.dayOfMonth, { onValueChange(value.withDayOfMonth(it.coerceIn(1, value.lengthOfMonth()))) }, range = 1..value.lengthOfMonth(),
                label = { "${it}日" }, enabled = enabled, visibleItemCount = 3, modifier = Modifier.weight(1f))
        }
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
