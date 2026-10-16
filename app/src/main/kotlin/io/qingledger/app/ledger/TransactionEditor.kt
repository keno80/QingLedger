package io.qingledger.app.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
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
fun TransactionEditor(state: LedgerUiState, previous: LedgerTransaction?, busy: Boolean, onSave: (TransactionDraft) -> Unit, onCancel: () -> Unit, requireKindSelection: Boolean = false, saveLabel: String = "保存", onManageCategories: (() -> Unit)? = null) {
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
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.background).padding(4.dp)) {
                TransactionKind.entries.forEach { next ->
                    val active = kindSelected && kind == next
                    Button(onClick = { if (kind != next) category = null; kindName = next.name; kindSelected = true; error = null }, enabled = !busy,
                        modifier = Modifier.weight(1f).height(32.dp).semantics { selected = active }, minWidth = 0.dp, minHeight = 32.dp,
                        cornerRadius = 10.dp, insideMargin = PaddingValues(0.dp), colors = ButtonDefaults.buttonColors(color = if (active) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent)) {
                        Text(next.label, fontSize = 12.sp, color = if (active) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("¥", fontSize = 22.sp)
                TextField(amount, { amount = it; error = null }, label = "0.00", useLabelAsPlaceholder = true, enabled = !busy, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.widthIn(max = 220.dp).weight(1f, fill = false).semantics { contentDescription = "金额（元）" },
                    insideMargin = DpSize(8.dp, 12.dp), colors = editorFieldColors(), cursorBrush = SolidColor(LedgerColors.accentContent),
                    textStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
            }
            if (kind != TransactionKind.TRANSFER) {
                Text("分类", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                val available = state.categories.filter { it.kind == kind && (!it.archived || it.id == previous?.categoryId) }
                val options = available.map { EditorCategory(it.id, it.name, categoryIcon(it.name)) } +
                    if (available.none { it.name == "未分类" }) listOf(EditorCategory(null, "未分类", LedgerIcons.Other)) else emptyList()
                val tiles = options + if (onManageCategories != null) listOf(EditorCategory("manage", "管理", LedgerIcons.Settings)) else emptyList()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(4).forEach { line ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            line.forEach { option ->
                                val active = option.id != "manage" && (category == option.id || (category == null && option.name == "未分类"))
                                IconButton(onClick = { if (option.id == "manage") { focus.clearFocus(); onManageCategories?.invoke() } else { category = option.id; error = null } }, enabled = !busy,
                                    modifier = Modifier.weight(1f).heightIn(min = 72.dp).semantics { selected = active }, cornerRadius = 14.dp,
                                    backgroundColor = if (active) LedgerColors.accentSurface else Color.Transparent) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(if (active) Color.Transparent else LedgerColors.accentSurface), contentAlignment = Alignment.Center) {
                                            Icon(option.icon, null, tint = LedgerColors.accentContent, modifier = Modifier.size(19.dp))
                                        }
                                        Text(option.name, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                            repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                Text("账户之间转账，不计入收入与支出。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (kind != TransactionKind.TRANSFER) EditorSelectionRow("资金账户", accountName(account), !busy) { openPanel("account") }
            else {
                EditorSelectionRow("转出账户", if (from == null) "请选择" else accountName(from), !busy) { openPanel("from") }
                EditorSelectionRow("转入账户", if (to == null) "请选择" else accountName(to), !busy) { openPanel("to") }
            }
            EditorInputRow("商户 / 标题", merchant, { merchant = it.take(128) }, !busy)
            EditorSelectionRow("日期与时间", "$date $time", !busy, showArrow = false) { openPanel("time") }
            EditorInputRow("备注", note, { note = it.take(500) }, !busy, singleLine = false, divider = false)
        }
        Text("金额、分类和账户放在同一页，少跳转一步。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        error?.let { Text(it, fontSize = 13.sp, color = LedgerColors.expense) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { focus.clearFocus(); onCancel() }, enabled = !busy, modifier = Modifier.weight(1f), minHeight = 48.dp) { Text("取消") }
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
    val panelTitle = when (panel) { "account" -> "选择资金账户"; "from" -> "选择转出账户"; "to" -> "选择转入账户"; else -> "交易时间" }
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
            val choices = listOf(null to if (panel == "account") "未指定账户" else "请选择") + state.accounts.filter { !it.archived || it.id == previous?.accountId || it.id == previous?.fromAccountId || it.id == previous?.toAccountId }.map { it.id to (it.name + if (it.archived) "（已归档）" else "") }
            val selectedId = when (panel) { "account" -> account; "from" -> from; else -> to }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(choices, key = { it.first ?: "none" }) { (id, label) ->
                    Button(onClick = {
                        when (panel) { "account" -> account = id; "from" -> from = id; "to" -> to = id }
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
private fun EditorSelectionRow(label: String, value: String, enabled: Boolean, showArrow: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 0.dp, insideMargin = PaddingValues(0.dp), onClick = { if (enabled) onClick() }) {
        Row(Modifier.heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.width(80.dp), fontSize = if (showArrow) 13.sp else 12.sp, color = if (showArrow) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.End)
            if (showArrow) Icon(LedgerIcons.Next, null, tint = LedgerColors.accentContent, modifier = Modifier.size(12.dp))
        }
    }
    EditorDivider()
}

@Composable
private fun EditorInputRow(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean, singleLine: Boolean = true, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(80.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Box(Modifier.weight(1f)) {
            TextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, insideMargin = DpSize(0.dp, 12.dp), colors = editorFieldColors(), cursorBrush = SolidColor(LedgerColors.accentContent), textStyle = TextStyle(fontSize = 12.sp, textAlign = TextAlign.End))
            if (value.isEmpty()) Text("可选", Modifier.align(Alignment.CenterEnd), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    if (divider) EditorDivider()
}

@Composable
private fun editorFieldColors() = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, borderColor = Color.Transparent, labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)

@Composable
private fun EditorDivider() { Box(Modifier.fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f))) }

private data class EditorCategory(val id: String?, val name: String, val icon: ImageVector)
private fun categoryIcon(name: String) = when (name) {
    "餐饮", "美食", "饮食" -> LedgerIcons.Dining
    "交通" -> LedgerIcons.Transport
    "购物" -> LedgerIcons.Shopping
    "居住", "住房" -> LedgerIcons.Housing
    "娱乐" -> LedgerIcons.Entertainment
    "医疗", "健康" -> LedgerIcons.Health
    else -> LedgerIcons.Other
}
