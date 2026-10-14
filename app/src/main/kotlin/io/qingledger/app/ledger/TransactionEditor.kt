package io.qingledger.app.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.qingledger.designsystem.component.StatusCard
import io.qingledger.domain.model.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import top.yukonga.miuix.kmp.basic.*

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

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChoiceGroup("账单类型", TransactionKind.entries.map { it.name to it.label }, kindName, { kindName = it; category = null; kindSelected = true }, !busy)
        TextField(amount, { amount = it }, label = "金额（元）", enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        TextField(merchant, { merchant = it.take(128) }, label = "商户或标题（可选）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (kind != TransactionKind.TRANSFER) {
            ChoiceGroup("分类", listOf(null to "未分类") + state.categories.filter { it.kind == kind && (!it.archived || it.id == previous?.categoryId) }.map { it.id to it.name }, category, { category = it }, !busy)
            ChoiceGroup("资金账户", listOf(null to "未指定") + state.accounts.filter { !it.archived || it.id == previous?.accountId }.map { it.id to it.name }, account, { account = it }, !busy)
        } else {
            val accounts = listOf(null to "请选择") + state.accounts.filter { !it.archived || it.id == previous?.fromAccountId || it.id == previous?.toAccountId }.map { it.id to it.name }
            ChoiceGroup("转出账户", accounts, from, { from = it }, !busy)
            ChoiceGroup("转入账户", accounts, to, { to = it }, !busy)
        }
        TextField(date, { date = it }, label = "日期（yyyy-MM-dd）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextField(time, { time = it }, label = "时间（HH:mm）", enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextField(note, { note = it.take(500) }, label = "备注（可选）", enabled = !busy, modifier = Modifier.fillMaxWidth())
        error?.let { StatusCard("请检查输入", it) }
        Button(onClick = {
            if (!kindSelected) { error = "请先主动选择账单类型。"; return@Button }
            val minor = LedgerRules.parseAmount(amount)
            if (minor == null) { error = "请输入有效金额，最多两位小数。"; return@Button }
            val localTime = try { LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)) }
            catch (_: DateTimeParseException) { error = "日期或时间格式不正确。"; return@Button }
            if (zone.rules.getValidOffsets(localTime).isEmpty()) { error = "该时间在所选时区不存在。"; return@Button }
            val draft = TransactionDraft(kind, minor, localTime.atZone(zone).toInstant().toEpochMilli(), merchant,
                categoryId = if (kind == TransactionKind.TRANSFER) null else category,
                accountId = if (kind == TransactionKind.TRANSFER) null else account,
                fromAccountId = if (kind == TransactionKind.TRANSFER) from else null,
                toAccountId = if (kind == TransactionKind.TRANSFER) to else null, note = note)
            error = LedgerRules.validate(draft)
            if (error == null) onSave(draft)
        }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary(), minHeight = 48.dp) { Text(if (busy) "保存中…" else saveLabel) }
        Button(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("取消") }
    }
}
