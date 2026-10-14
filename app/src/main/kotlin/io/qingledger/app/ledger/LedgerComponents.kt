package io.qingledger.app.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*

@Composable
fun <T> ChoiceGroup(label: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, text) ->
                Button(onClick = { onSelect(value) }, enabled = enabled, minHeight = 48.dp,
                    colors = if (value == selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    modifier = Modifier.semantics { this.selected = value == selected },
                ) { Text(text) }
            }
        }
    }
}

@Composable
fun MonthSelector(state: LedgerUiState, viewModel: LedgerViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Button(onClick = { viewModel.changeMonth(-1) }, minHeight = 48.dp) { Text("上月") }
        Text("${state.selection.month.year} 年 ${state.selection.month.monthValue} 月", modifier = Modifier.padding(top = 14.dp))
        Button(onClick = { viewModel.changeMonth(1) }, minHeight = 48.dp) { Text("下月") }
    }
}
