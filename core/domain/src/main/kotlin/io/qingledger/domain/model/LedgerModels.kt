package io.qingledger.domain.model

import java.time.YearMonth
import java.time.ZoneId
import java.math.BigDecimal

enum class TransactionKind(val label: String) { EXPENSE("支出"), INCOME("收入"), TRANSFER("转账") }
data class Category(val id: String, val name: String, val kind: TransactionKind, val archived: Boolean = false)
data class Account(val id: String, val name: String, val archived: Boolean = false, val openingBalanceMinor: Long? = null)
data class LedgerTransaction(
    val id: String,
    val kind: TransactionKind,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String,
    val categoryId: String?,
    val accountId: String?,
    val fromAccountId: String?,
    val toAccountId: String?,
    val note: String,
    val revision: Int,
    val zoneId: String,
    val sourcePackage: String? = null,
)
data class TransactionDraft(
    val kind: TransactionKind,
    val amountMinor: Long,
    val occurredAt: Long,
    val merchant: String = "",
    val categoryId: String? = null,
    val accountId: String? = null,
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val note: String = "",
)
data class PeriodRange(val start: Long, val end: Long)
data class LedgerSummary(val income: Long = 0, val expense: Long = 0) {
    val balance: Long get() = Math.subtractExact(income, expense)
}

object LedgerRules {
    const val MAX_AMOUNT = 999_999_999_999L
    fun parseAmount(text: String): Long? {
        val value = text.trim()
        if (!Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(value)) return null
        return try { BigDecimal(value).movePointRight(2).longValueExact().takeIf { it in 1..MAX_AMOUNT } }
        catch (_: ArithmeticException) { null }
    }
    fun period(month: YearMonth, zone: ZoneId): PeriodRange = PeriodRange(
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
    fun validate(draft: TransactionDraft): String? = when {
        draft.amountMinor !in 1..MAX_AMOUNT -> "金额需大于 0 且不超过 9999999999.99 元。"
        draft.merchant.length > 128 -> "商户名称最多 128 个字符。"
        draft.note.length > 500 -> "备注最多 500 个字符。"
        draft.kind == TransactionKind.TRANSFER && (draft.fromAccountId == null || draft.toAccountId == null) -> "请选择转出和转入账户。"
        draft.kind == TransactionKind.TRANSFER && draft.fromAccountId == draft.toAccountId -> "转出和转入账户不能相同。"
        draft.kind == TransactionKind.TRANSFER && (draft.accountId != null || draft.categoryId != null) -> "转账不能同时指定收支账户或分类。"
        draft.kind != TransactionKind.TRANSFER && (draft.fromAccountId != null || draft.toAccountId != null) -> "收支账单不能带有转账账户。"
        else -> null
    }
    fun summarize(transactions: List<LedgerTransaction>): LedgerSummary {
        var income = 0L
        var expense = 0L
        transactions.forEach {
            when (it.kind) {
                TransactionKind.INCOME -> income = Math.addExact(income, it.amountMinor)
                TransactionKind.EXPENSE -> expense = Math.addExact(expense, it.amountMinor)
                TransactionKind.TRANSFER -> Unit
            }
        }
        return LedgerSummary(income, expense)
    }
}
