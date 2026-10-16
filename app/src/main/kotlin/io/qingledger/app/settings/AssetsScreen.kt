package io.qingledger.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.ledger.AssetsUiState
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AssetsScreen(state: AssetsUiState, onManage: () -> Unit, onAccount: (String) -> Unit) {
    if (state.loading) { StatusCard("正在读取资产", "请稍候。"); return }
    state.error?.let { StatusCard("未能读取资产", it); return }
    val known = state.summary.configuredCount > 0
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("资产概览", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${state.rows.size}个账户", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(18.dp))
            Text("净资产", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(if (known) formatMoney(state.summary.netMinor) else "—", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            Row(Modifier.fillMaxWidth()) {
                AssetMetric("总资产", if (known) formatMoney(state.summary.assetsMinor) else "—", Modifier.weight(1f), true)
                AssetMetric("总负债", if (known) formatMoney(state.summary.liabilitiesMinor) else "—", Modifier.weight(1f), false)
            }
        }
        if (state.summary.configuredCount < state.summary.accountCount) Text("已设置初始余额 ${state.summary.configuredCount}/${state.summary.accountCount}，未设置的账户不计入概览。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SettingsSection("资金账户") {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) SettingsRowDivider()
                SettingsNavigationRow(row.account.name + if (row.account.archived) "（已归档）" else "", if (row.account.openingBalanceMinor == null) "未设置初始余额" else "初始余额 + 关联账单", LedgerIcons.forAccount(row.account.name), { onAccount(row.account.id) }) {
                    val balance = row.balanceMinor
                    Text(balance?.let(::formatMoney) ?: "未设置", fontSize = if (balance == null) 11.sp else 14.sp, color = when { balance == null -> MiuixTheme.colorScheme.onSurfaceVariantSummary; balance < 0 -> LedgerColors.expense; else -> LedgerColors.income })
                }
            }
            if (state.rows.isEmpty()) Text("暂无账户", Modifier.padding(16.dp), fontSize = 13.sp)
        }
        Button(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("管理账户") }
        Text("余额按初始余额与全部关联账单计算，包含已归档账户；未指定账户的账单不计入。账单不完整时，余额可能与实际持有金额不同。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun AssetMetric(title: String, value: String, modifier: Modifier, income: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 16.sp, color = if (income) LedgerColors.income else MiuixTheme.colorScheme.onSurface)
    }
}
