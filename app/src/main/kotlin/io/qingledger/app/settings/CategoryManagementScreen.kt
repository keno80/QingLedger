package io.qingledger.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.ledger.LedgerUiState
import io.qingledger.app.ledger.LedgerViewModel
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.component.LedgerSegmentedControl
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.ReferenceRules
import io.qingledger.domain.model.TransactionKind
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CategoryManagementScreen(state: LedgerUiState, ledger: LedgerViewModel, busy: Boolean) {
    var kindName by rememberSaveable { mutableStateOf(TransactionKind.EXPENSE.name) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var formKindName by rememberSaveable { mutableStateOf(kindName) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val current = state.categories.find { it.id == selectedId }
    val rows = state.categories.filter { it.kind.name == kindName && it.archived == archived }
    fun openForm(id: String? = null) {
        val row = state.categories.find { it.id == id }
        editingId = id; name = row?.name ?: ""; formKindName = row?.kind?.name ?: kindName
        error = null; panel = "form"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryKindSelector(kindName, !busy) { kindName = it }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${if (archived) "已归档" else "使用中"} · ${rows.size}个分类", Modifier.weight(1f), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(text = if (archived) "查看使用中" else "查看归档", onClick = { if (!busy) archived = !archived }, minHeight = 32.dp, insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
        }
        if (state.loading) Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(20.dp)) { Text("正在读取分类…", fontSize = 13.sp) }
        else if (rows.isEmpty()) Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(20.dp)) {
            Text(if (archived) "暂无已归档分类" else "暂无${TransactionKind.valueOf(kindName).label}分类", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(if (archived) "归档后可在这里恢复使用。" else "点击下方按钮添加一个分类。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        rows.forEach { row ->
            Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp), onClick = { if (!busy) { selectedId = row.id; panel = "actions" } }) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(LedgerColors.accentSurface), contentAlignment = Alignment.Center) {
                        Icon(LedgerIcons.forCategory(row.name), null, tint = LedgerColors.accentContent, modifier = Modifier.size(19.dp))
                    }
                    Text(row.name, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = { selectedId = row.id; panel = "actions" }, enabled = !busy, modifier = Modifier.size(40.dp).semantics { contentDescription = "管理${row.name}" }) {
                        Text("···", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        Button(onClick = { openForm() }, enabled = !busy && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), minHeight = 48.dp, colors = ButtonDefaults.buttonColorsPrimary()) {
            Icon(LedgerIcons.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("新增分类")
        }
    }
    val title = when (panel) { "form" -> if (editingId == null) "新增分类" else "修改名称"; "archive" -> "归档分类"; else -> current?.name ?: "管理分类" }
    OverlayBottomSheet(show = panel != null, title = title, onDismissRequest = { if (!busy) { focus.clearFocus(); panel = null } }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (panel) {
                "form" -> {
                    TextField(name, { name = it; error = null }, label = "分类名称（1–32 字）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    CategoryKindSelector(formKindName, !busy && editingId == null) { formKindName = it }
                    if (editingId != null) Text("改名会同步更新已有账单的分类显示。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    error?.let { Text(it, fontSize = 13.sp, color = LedgerColors.expense) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { focus.clearFocus(); panel = null }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = {
                            if (ReferenceRules.normalizeName(name) == null) error = "名称需为 1–32 字，不能包含换行或控制字符。"
                            else {
                                focus.clearFocus(); error = null
                                ledger.saveCategory(name, TransactionKind.valueOf(formKindName), editingId) { name = ""; editingId = null; panel = null }
                            }
                        }, enabled = !busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (busy) "保存中…" else "保存") }
                    }
                }
                "archive" -> {
                    Text("归档“${current?.name.orEmpty()}”后，新账单不再选用这个分类，已有账单保持完整。", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { panel = null }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(onClick = { current?.let { ledger.archiveReference(it.id, true) }; panel = null }, enabled = !busy && current != null, modifier = Modifier.weight(1f)) { Text("归档", color = LedgerColors.expense) }
                    }
                }
                else -> {
                    if (current?.archived == true) Button(onClick = { current.let { ledger.restoreReference(it.id, true) }; panel = null }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("恢复使用") }
                    else {
                        Button(onClick = { current?.let { openForm(it.id) } }, enabled = !busy && current != null, modifier = Modifier.fillMaxWidth()) { Text("修改名称") }
                        Button(onClick = { panel = "archive" }, enabled = !busy && current != null, modifier = Modifier.fillMaxWidth()) { Text("归档分类", color = LedgerColors.expense) }
                    }
                    Button(onClick = { panel = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun CategoryKindSelector(value: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val kinds = listOf(TransactionKind.EXPENSE, TransactionKind.INCOME)
    LedgerSegmentedControl(kinds.map { it.label }, kinds.indexOfFirst { it.name == value }, { onSelect(kinds[it].name) }, enabled = enabled)
}
