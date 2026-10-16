package io.qingledger.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.ledger.*
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.AssetRules
import io.qingledger.domain.model.ReferenceRules
import java.math.BigDecimal
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AccountManagementScreen(state: LedgerUiState, ledger: LedgerViewModel, busy: Boolean, focusAccountId: String? = null) {
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var opening by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var focused by rememberSaveable { mutableStateOf(false) }
    val saveMessage by ledger.message.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    fun openForm(id: String? = null) {
        ledger.clearMessage()
        val account = state.accounts.firstOrNull { it.id == id }
        editingId = id; name = account?.name.orEmpty()
        opening = account?.openingBalanceMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
        error = null; panel = "form"
    }
    LaunchedEffect(state.loading, focusAccountId) {
        if (!state.loading && focusAccountId != null && !focused) {
            val account = state.accounts.firstOrNull { it.id == focusAccountId }
            if (account != null) { if (account.archived) { editingId = account.id; panel = "restore" } else openForm(account.id) }
            focused = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection("使用中的账户") {
            val active = state.accounts.filter { !it.archived }
            active.forEachIndexed { index, account ->
                if (index > 0) SettingsRowDivider()
                SettingsNavigationRow(account.name, account.openingBalanceMinor?.let { "初始余额 ${formatMoney(it)}" } ?: "初始余额未设置", LedgerIcons.forAccount(account.name), { openForm(account.id) })
            }
            if (active.isEmpty()) Text("暂无使用中的账户", Modifier.padding(16.dp), fontSize = 13.sp)
        }
        val archived = state.accounts.filter { it.archived }
        if (archived.isNotEmpty()) SettingsSection("已归档") {
            archived.forEachIndexed { index, account ->
                if (index > 0) SettingsRowDivider()
                SettingsNavigationRow(account.name, "历史账单仍保留引用", LedgerIcons.Wallet, { editingId = account.id; panel = "restore" }) { Text("恢复", fontSize = 11.sp, color = LedgerColors.accentContent) }
            }
        }
        Button(onClick = { openForm() }, enabled = !busy && !state.loading, modifier = Modifier.fillMaxWidth()) { Text("新增账户") }
        Text("渠道和资金账户分别管理，微信零钱与不同银行卡独立记账。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    LedgerBottomSheet(panel != null, when (panel) { "archive" -> "归档账户"; "restore" -> "恢复账户"; else -> if (editingId == null) "新增账户" else "编辑账户" }, { if (!busy) { focus.clearFocus(); panel = null } }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (panel) {
                "form" -> {
                    TextField(name, { name = it; error = null }, label = "账户名称（1–32 字）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(opening, { opening = it; error = null }, label = "初始余额（元，可留空）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("填写最早一笔已记录账单之前的余额，不能填写当前余额。支持 0 和负数；留空表示未知，并从资产概览中排除。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    error?.let { Text(it, fontSize = 12.sp, color = LedgerColors.expense) }
                    if (error == null) saveMessage?.let { Text(it, fontSize = 12.sp, color = LedgerColors.expense) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { focus.clearFocus(); panel = null }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = {
                            val amount = if (opening.isBlank()) null else AssetRules.parseOpeningBalance(opening)
                            error = when { ReferenceRules.normalizeName(name) == null -> "名称需为 1–32 字，不能包含换行或控制字符。"; opening.isNotBlank() && amount == null -> "请输入有效金额，最多两位小数，绝对值不超过 9999999999.99 元。"; else -> null }
                            if (error == null) { focus.clearFocus(); ledger.saveAccountDetails(name, amount, editingId) { panel = null } }
                        }, enabled = !busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (busy) "保存中…" else "保存") }
                    }
                    if (editingId != null) Button(onClick = { focus.clearFocus(); panel = "archive" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("归档账户", color = LedgerColors.expense) }
                }
                else -> {
                    Text(if (panel == "restore") "恢复后可继续选择该账户，原有账单和初始余额保留。" else "归档后新账单不再选用该账户，已有账单与资产余额仍保留。", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { panel = null }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = { editingId?.let { if (panel == "restore") ledger.restoreReference(it, false) else ledger.archiveReference(it, false) }; panel = null }, enabled = !busy && editingId != null, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (panel == "restore") "恢复" else "归档") }
                    }
                }
            }
        }
    }
}
