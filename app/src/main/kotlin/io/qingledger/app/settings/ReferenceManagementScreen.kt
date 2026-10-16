package io.qingledger.app.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qingledger.app.ledger.*
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.TransactionKind
import top.yukonga.miuix.kmp.basic.*

@Composable
fun ReferenceManagementScreen(state: LedgerUiState, ledger: LedgerViewModel, category: Boolean, busy: Boolean, onBack: () -> Unit, onFocusForm: () -> Unit) {
    if (category) {
        CategoryManagementScreen(state, ledger, busy)
        return
    }
    var name by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var kindName by rememberSaveable { mutableStateOf(TransactionKind.EXPENSE.name) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var archiveId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val label = if (category) "分类" else "账户"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusCard("管理$label", "改名会同步显示在关联账单中。归档不会删除账单，恢复后可继续使用。")
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (editingId == null) "新增$label" else "改名$label")
                TextField(name, { name = it }, label = "${label}名称（最多 32 字）", singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                if (category) ChoiceGroup("收支类型", listOf("EXPENSE" to "支出", "INCOME" to "收入"), kindName, { kindName = it }, !busy && editingId == null)
                error?.let { Text(it) }
                Button(onClick = {
                    if (io.qingledger.domain.model.ReferenceRules.normalizeName(name) == null) {
                        error = "名称需为 1–32 字，不能包含换行或控制字符。"
                    } else {
                        error = null
                        val reset = { name = ""; editingId = null }
                        if (category) ledger.saveCategory(name, TransactionKind.valueOf(kindName), editingId, reset)
                        else ledger.saveAccount(name, editingId, reset)
                    }
                }, enabled = !busy && !state.loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary(), minHeight = 48.dp) { Text(if (editingId == null) "新增$label" else "保存改名") }
                if (editingId != null) Button(onClick = { editingId = null; name = ""; error = null }, enabled = !busy) { Text("取消改名") }
            }
        }
        ChoiceGroup("显示范围", listOf(false to "使用中", true to "已归档"), showArchived, { showArchived = it; archiveId = null }, !busy)
        if (archiveId != null) {
            StatusCard("确认归档？", "已有账单保持完整，新账单不再选用这项$label。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ledger.archiveReference(archiveId!!, category); archiveId = null }, enabled = !busy) { Text("确认归档") }
                Button(onClick = { archiveId = null }, enabled = !busy) { Text("取消归档") }
            }
        }
        val rows = if (category) state.categories.filter { it.archived == showArchived }.map { Triple(it.id, it.name, it.kind.label) }
        else state.accounts.filter { it.archived == showArchived }.map { Triple(it.id, it.name, "资金账户") }
        if (rows.isEmpty()) Text("暂无${if (showArchived) "已归档" else "使用中"}$label")
        rows.forEach { (id, title, summary) ->
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsDescription(title, summary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showArchived) Button(onClick = { ledger.restoreReference(id, category) }, enabled = !busy) { Text("恢复$title") }
                        else {
                            Button(onClick = { editingId = id; name = title; error = null; if (category) kindName = state.categories.first { it.id == id }.kind.name; onFocusForm() }, enabled = !busy) { Text("改名$title") }
                            Button(onClick = { archiveId = id; onFocusForm() }, enabled = !busy && editingId == null) { Text("归档$title") }
                        }
                    }
                }
            }
        }
        Button(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("返回我的") }
    }
}
