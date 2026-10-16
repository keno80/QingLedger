package io.qingledger.domain

import io.qingledger.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class RefundRulesTest {
    private fun expense() = LedgerTransaction("expense", TransactionKind.EXPENSE, 10000, 1, "商户", "food", "cash", null, null, "", 0, "Asia/Shanghai")
    private fun refund(id: String = "refund", amount: Long = 3000) = expense().copy(id = id, kind = TransactionKind.REFUND, amountMinor = amount, occurredAt = 2, originalExpenseId = "expense")
    @Test fun refundReducesExpensesAndRestoresCashWithoutIncreasingIncome() {
        val summary = LedgerRules.summarize(listOf(expense(), refund()))
        assertEquals(0L, summary.income); assertEquals(7000L, summary.expense)
        assertEquals(-7000L, summary.balance)
        assertEquals(-3000L, LedgerRules.summarize(listOf(refund())).expense)
    }
    @Test fun linksMustReferenceAnExpenseAndCannotOverRefundAcrossMultipleRows() {
        val original = expense()
        assertNull(RefundRules.validate(listOf(original, refund(), refund("refund-2", 7000))))
        assertNotNull(RefundRules.validate(listOf(original, refund(), refund("refund-2", 7001))))
        assertNotNull(RefundRules.validate(listOf(refund())))
        assertNotNull(RefundRules.validate(listOf(original.copy(kind = TransactionKind.INCOME), refund())))
        assertNotNull(RefundRules.validate(listOf(original, refund().copy(categoryId = "other"))))
    }
    @Test fun standaloneRefundUsesExpenseCategoryWithoutRequiringOriginal() {
        assertNull(RefundRules.validate(listOf(refund().copy(originalExpenseId = null))))
        assertEquals(TransactionKind.EXPENSE, TransactionKind.REFUND.categoryKind)
    }
}
