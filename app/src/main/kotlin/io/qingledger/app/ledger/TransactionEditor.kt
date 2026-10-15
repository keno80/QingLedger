package io.qingledger.app.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TransactionEditor(state: LedgerUiState, previous: LedgerTransaction?, busy: Boolean, onSave: (TransactionDraft) -> Unit, onCancel: () -> Unit, requireKindSelection: Boolean = false, saveLabel: String = "保存账单") {
    val zone = ZoneId.of(previous?.zoneId ?: state.zoneId)
    val initialTime = previous?.occurredAt?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: ZonedDateTime.now(zone)
    var kindName by rememberSaveable { mutableStateOf(previous?.kind?.name ?: TransactionKind.EXPENSE.name) }
    val kind = TransactionKind.valueOf(kindName)
    var kindSelected by rememberSaveable { mutableStateOf(!requireKindSelection) }
    var amount by rememberSaveable { mutableStateOf(previous?.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() } ?: "") }
    var merchant by rememberSaveable { mutableStateOf(previous?.merchant ?: "") }
    var note by rememberSaveable { mutableStateOf(previous?.note ?: "") }
    var date by rememberSaveable { mutableStateOf(initialTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) }
    var time by rememberSaveable { mutableStateOf(initialTime.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    var category by rememberSaveable { mutableStateOf(previous?.categoryId) }
    var account by rememberSaveable { mutableStateOf(previous?.accountId) }
    var from by rememberSaveable { mutableStateOf(previous?.fromAccountId) }
    var to by rememberSaveable { mutableStateOf(previous?.toAccountId) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var timeError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDate by rememberSaveable { mutableStateOf(date) }
    var pendingTime by rememberSaveable { mutableStateOf(time) }
    val focus = LocalFocusManager.current
    fun openPanel(value: String) { focus.clearFocus(); pendingDate = date; pendingTime = time; panel = value; timeError = null }
    fun accountName(id: String?) = state.accounts.find { it.id == id }?.name ?: "未指定账户"
    fun parseTime(day: String = date, clock: String = time): LocalDateTime? = try { LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(clock)) } catch (_: DateTimeParseException) { null }
    val categoryName = state.categories.find { it.id == category }?.name ?: "未分类"

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransactionKind.entries.forEach { item ->
                    val active = kindSelected && kind == item
                    Button(onClick = { if (kind != item) category = null; kindName = item.name; kindSelected = true; error = null }, enabled = !busy,
                        modifier = Modifier.weight(1f).semantics { selected = active }, minHeight = 40.dp,
                        colors = if (active) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(item.label, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            TextField(amount, { amount = it; error = null }, label = "金额（元）", enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp,
                textStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = when (kind) { TransactionKind.EXPENSE -> LedgerColors.expense; TransactionKind.INCOME -> LedgerColors.income; TransactionKind.TRANSFER -> MiuixTheme.colorScheme.onSurface }))
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(16.dp)) {
            TextField(merchant, { merchant = it.take(128) }, label = "商户或标题（可选）", enabled = !busy, singleLine = true,
                modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, textStyle = TextStyle(fontSize = 14.sp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (kind != TransactionKind.TRANSFER) {
                EditorSelectionRow("分类", categoryName, !busy) { openPanel("category") }
                EditorSelectionRow("资金账户", accountName(account), !busy) { openPanel("account") }
            } else {
                EditorSelectionRow("转出账户", if (from == null) "请选择" else accountName(from), !busy) { openPanel("from") }
                EditorSelectionRow("转入账户", if (to == null) "请选择" else accountName(to), !busy) { openPanel("to") }
            }
            EditorSelectionRow("交易时间", "$date $time", !busy) { openPanel("time") }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(16.dp)) {
            TextField(note, { note = it.take(500) }, label = "备注（可选）", enabled = !busy, modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, textStyle = TextStyle(fontSize = 14.sp))
            Text("${note.length}/500", Modifier.align(Alignment.End).padding(top = 8.dp), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        error?.let { Text(it, fontSize = 13.sp, color = LedgerColors.expense) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f), minHeight = 48.dp) { Text("取消") }
            Button(onClick = {
                focus.clearFocus()
                if (!kindSelected) { error = "请先主动选择账单类型。"; return@Button }
                val minor = LedgerRules.parseAmount(amount)
                if (minor == null) { error = "请输入有效金额，最多两位小数。"; return@Button }
                val localTime = parseTime()
                if (localTime == null) { error = "日期或时间格式不正确。"; return@Button }
                if (zone.rules.getValidOffsets(localTime).isEmpty()) { error = "该时间在所选时区不存在。"; return@Button }
                val draft = TransactionDraft(kind, minor, localTime.atZone(zone).toInstant().toEpochMilli(), merchant,
                    categoryId = if (kind == TransactionKind.TRANSFER) null else category,
                    accountId = if (kind == TransactionKind.TRANSFER) null else account,
                    fromAccountId = if (kind == TransactionKind.TRANSFER) from else null,
                    toAccountId = if (kind == TransactionKind.TRANSFER) to else null, note = note)
                error = LedgerRules.validate(draft)
                if (error == null) onSave(draft)
            }, enabled = !busy, modifier = Modifier.weight(1.8f), colors = ButtonDefaults.buttonColorsPrimary(), minHeight = 48.dp) { Text(if (busy) "保存中…" else saveLabel) }
        }
    }
    val panelTitle = when (panel) { "category" -> "选择分类"; "account" -> "选择资金账户"; "from" -> "选择转出账户"; "to" -> "选择转入账户"; else -> "交易时间" }
    OverlayBottomSheet(show = panel != null, title = panelTitle, onDismissRequest = { if (!busy) panel = null }) {
        if (panel == "time") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextField(pendingDate, { pendingDate = it; timeError = null }, label = "日期（yyyy-MM-dd）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextField(pendingTime, { pendingTime = it; timeError = null }, label = "时间（HH:mm）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                timeError?.let { Text(it, fontSize = 13.sp, color = LedgerColors.expense) }
                Button(onClick = {
                    val parsed = parseTime(pendingDate, pendingTime)
                    timeError = when { parsed == null -> "日期或时间格式不正确。"; zone.rules.getValidOffsets(parsed).isEmpty() -> "该时间在所选时区不存在。"; else -> null }
                    if (timeError == null) { date = pendingDate; time = pendingTime; focus.clearFocus(); panel = null; error = null }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("完成") }
            }
        } else {
            val choices = if (panel == "category") listOf(null to "不指定分类") + state.categories.filter { it.kind == kind && (!it.archived || it.id == previous?.categoryId) }.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }
                else listOf(null to if (panel == "account") "未指定账户" else "请选择") + state.accounts.filter { !it.archived || it.id == previous?.accountId || it.id == previous?.fromAccountId || it.id == previous?.toAccountId }.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }
            val selectedId = when (panel) { "category" -> category; "account" -> account; "from" -> from; else -> to }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(choices, key = { it.first ?: "none" }) { (id, label) ->
                    Button(onClick = {
                        when (panel) { "category" -> category = id; "account" -> account = id; "from" -> from = id; "to" -> to = id }
                        panel = null; error = null
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { selected = id == selectedId },
                        colors = if (id == selectedId) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 14.sp)
                            if (id == selectedId) Text("已选择", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSelectionRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp, insideMargin = PaddingValues(horizontal = 20.dp, vertical = 16.dp), onClick = { if (enabled) onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.width(64.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, Modifier.weight(1f), fontSize = 14.sp)
            Icon(LedgerIcons.Next, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
        }
    }
}
