package io.qingledger.app.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qingledger.app.ledger.*
import io.qingledger.designsystem.component.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun StatisticsScreen(state: LedgerUiState, viewModel: LedgerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MonthSelector(state, viewModel)
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("本月净支出")
                AmountText(state.summary.expense)
                SettingsDescription("本月收入", formatMoney(state.summary.income))
                SettingsDescription("本月结余", formatMoney(state.summary.balance))
            }
        }
        StatusCard("统计口径", "统计当前月份全部账单，转账不计入收支，退款按到账日期冲减支出。")
    }
}
