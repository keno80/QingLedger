package io.qingledger.app.captureconfirmation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.LedgerSegmentedControl
import io.qingledger.designsystem.component.formatMoney
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.math.BigDecimal
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One candidate owns its draft; background enrichment never overwrites user edits. */
@Composable
fun ConfirmationContent(candidate: PendingCandidate, categories: List<Category>, accounts: List<Account>, expenses: List<LedgerTransaction>,
    busy: Boolean, failure: String?, onDismiss: () -> Unit, onConfirm: (TransactionDraft, String?) -> Unit) {
    val kinds = listOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.REFUND)
    fun money(value: Long?) = value?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()
    var kindText by rememberSaveable(candidate.id) { mutableStateOf((candidate.kind?.takeIf { it in kinds } ?: TransactionKind.EXPENSE).name) }
    val kind = TransactionKind.valueOf(kindText)
    var amount by rememberSaveable(candidate.id) { mutableStateOf(money(candidate.amountMinor)) }
    var merchant by rememberSaveable(candidate.id) { mutableStateOf(candidate.merchant.orEmpty()) }
    var note by rememberSaveable(candidate.id) { mutableStateOf("") }
    var original by rememberSaveable(candidate.id) { mutableStateOf(money(candidate.originalAmountMinor)) }
    var saving by rememberSaveable(candidate.id) { mutableStateOf(money(candidate.discountMinor)) }
    var category by rememberSaveable(candidate.id) { mutableStateOf<String?>(null) }
    var expense by rememberSaveable(candidate.id) { mutableStateOf<String?>(null) }
    val detected = remember(candidate.id) { candidate.accountName?.let(PaymentAccountRules::canonicalName) }
    val initialAccount = remember(candidate.id) {
        val exact = accounts.filter { detected != null && it.name.equals(detected, true) }
        val identity = detected?.let(PaymentAccountRules::bankIdentity)
        val matches = if (exact.isNotEmpty() || identity == null) exact else accounts.filter {
            PaymentAccountRules.bankIdentity(it.name) == identity && (PaymentAccountRules.unspecifiedBankType(detected!!) || PaymentAccountRules.unspecifiedBankType(it.name))
        }
        when {
            matches.size == 1 && !matches.single().archived -> matches.single().id
            matches.isNotEmpty() -> null
            detected != null -> DETECTED_ACCOUNT
            else -> null
        }
    }
    var account by rememberSaveable(candidate.id) { mutableStateOf(initialAccount) }
    var choice by rememberSaveable(candidate.id) { mutableStateOf<String?>(null) }
    var error by rememberSaveable(candidate.id) { mutableStateOf<String?>(null) }
    fun accountName() = if (account == DETECTED_ACCOUNT) detected.orEmpty() else accounts.find { it.id == account }?.name ?: "未指定账户"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("确认记账", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.size(40.dp)) { Text("×", fontSize = 24.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("¥", fontSize = 22.sp)
            TextField(amount, { amount = it; error = null }, modifier = Modifier.width(190.dp).semantics { contentDescription = "金额（元）" },
                enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                insideMargin = DpSize(8.dp, 6.dp), colors = transparentFields(), cursorBrush = SolidColor(LedgerColors.accentContent),
                textStyle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            if (choice == null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("入账类型", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    LedgerSegmentedControl(kinds.map { it.label }, kinds.indexOf(kind), { next ->
                        val selected = kinds[next]
                        if (selected.categoryKind != kind.categoryKind) category = null
                        if (selected != TransactionKind.REFUND) expense = null
                        kindText = selected.name; error = null
                    }, Modifier.weight(1f), enabled = !busy)
                }
                Divider()
                SelectRow("分类", categories.find { it.id == category }?.name ?: "未分类", !busy) { choice = "category" }
                SelectRow("资金账户", accountName(), !busy) { choice = "account" }
                InputRow("商户 / 对方", merchant, { merchant = it.take(128) }, !busy)
                if (kind == TransactionKind.REFUND) SelectRow("关联支出", expenses.find { it.id == expense }?.let { "${it.merchant.ifBlank { "支出" }} · ${formatMoney(it.amountMinor)}" } ?: "未关联", !busy) { choice = "expense" }
                if (kind == TransactionKind.EXPENSE) {
                    InputRow("原价（元）", original, { original = it }, !busy, decimal = true)
                    InputRow("优惠（元）", saving, { saving = it }, !busy, decimal = true)
                }
                InputRow("备注", note, { note = it.take(500) }, !busy, divider = false)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (choice) { "account" -> "资金账户"; "expense" -> "关联支出"; else -> "分类" }, Modifier.weight(1f), fontSize = 15.sp)
                    Button(onClick = { choice = null }, minHeight = 32.dp) { Text("返回") }
                }
                when (choice) {
                    "account" -> {
                        Choice("未指定账户", account == null) { account = null; choice = null }
                        if (detected != null && initialAccount == DETECTED_ACCOUNT) Choice(detected, account == DETECTED_ACCOUNT) { account = DETECTED_ACCOUNT; choice = null }
                        accounts.filter { !it.archived }.forEach { row -> Choice(row.name, account == row.id) { account = row.id; choice = null } }
                    }
                    "expense" -> {
                        Choice("不关联", expense == null) { expense = null; choice = null }
                        expenses.filter { it.kind == TransactionKind.EXPENSE }.take(200).forEach { row ->
                            Choice("${row.merchant.ifBlank { "支出" }} · ${formatMoney(row.amountMinor)}", expense == row.id) { expense = row.id; category = row.categoryId; choice = null }
                        }
                    }
                    else -> {
                        Choice("未分类", category == null) { category = null; choice = null }
                        categories.filter { !it.archived && it.kind == kind.categoryKind && it.name != "未分类" }.forEach { row -> Choice(row.name, category == row.id) { category = row.id; choice = null } }
                    }
                }
            }
        }
        (error ?: failure)?.let { Text(it, Modifier.padding(top = 12.dp), fontSize = 12.sp, color = LedgerColors.expense) }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f), minHeight = 48.dp) { Text("取消") }
            Button(onClick = {
                val paid = LedgerRules.parseAmount(amount)
                val originalMinor = if (kind == TransactionKind.EXPENSE && original.isNotBlank()) PaymentDiscountRules.parseAmount(original) else null
                val discountMinor = if (kind == TransactionKind.EXPENSE && saving.isNotBlank()) PaymentDiscountRules.parseAmount(saving) else null
                error = when {
                    paid == null -> "请输入有效金额，最多两位小数。"
                    kind == TransactionKind.EXPENSE && (original.isNotBlank() && originalMinor == null || saving.isNotBlank() && discountMinor == null) -> "原价或优惠金额格式无效。"
                    else -> null
                }
                if (error == null) {
                    val draft = TransactionDraft(kind, paid!!, System.currentTimeMillis(), merchant, categoryId = category,
                        accountId = account.takeUnless { it == DETECTED_ACCOUNT }, note = note, originalExpenseId = expense.takeIf { kind == TransactionKind.REFUND },
                        originalAmountMinor = originalMinor, discountMinor = discountMinor)
                    error = LedgerRules.validate(draft)
                    if (error == null) onConfirm(draft, detected.takeIf { account == DETECTED_ACCOUNT })
                }
            }, enabled = !busy && choice == null, modifier = Modifier.weight(1f), minHeight = 48.dp, colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (busy) "保存中…" else "确认入账") }
        }
    }
}

private const val DETECTED_ACCOUNT = "__recognized_account__"
@Composable private fun transparentFields() = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent, borderColor = Color.Transparent, labelColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
@Composable private fun Divider() { Box(Modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .12f))) }
@Composable private fun InputRow(label: String, value: String, change: (String) -> Unit, enabled: Boolean, decimal: Boolean = false, divider: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(80.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Box(Modifier.weight(1f).padding(end = 20.dp)) {
            // miuix 0.9.4 chrome wraps its inner editor at Start. Position that chrome
            // at the right edge while keeping the edited text's own direction LTR-aware.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                TextField(value, change, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }, insideMargin = DpSize(0.dp, 10.dp),
                    colors = transparentFields(), keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text),
                    cursorBrush = SolidColor(LedgerColors.accentContent), textStyle = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Right, textDirection = TextDirection.ContentOrLtr))
            }
        }
    }
    if (divider) Divider()
}
@Composable private fun SelectRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 0.dp, insideMargin = PaddingValues(0.dp), onClick = { if (enabled) onClick() }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.width(80.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.End)
            Text("›", Modifier.width(12.dp), fontSize = 17.sp, textAlign = TextAlign.Center, color = LedgerColors.accentContent)
        }
    }
    Divider()
}
@Composable private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(label, fontSize = 13.sp) }
}
