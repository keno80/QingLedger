package io.qingledger.domain.model

object RefundRules {
    /** Only live transactions participate. Missing/soft-deleted originals must be explicitly unlinked. */
    fun validate(rows: List<LedgerTransaction>): String? {
        val byId = rows.associateBy { it.id }
        val totals = mutableMapOf<String, Long>()
        for (row in rows) {
            val id = row.originalExpenseId ?: continue
            if (row.kind != TransactionKind.REFUND) return "只有退款可以关联原支出。"
            val original = byId[id] ?: return "退款关联的原支出不存在，请先解除关联。"
            if (original.kind != TransactionKind.EXPENSE) return "退款只能关联支出账单。"
            if (row.occurredAt < original.occurredAt) return "退款时间不能早于原支出。"
            if (row.categoryId != original.categoryId) return "关联退款需与原支出使用同一分类，请调整分类或解除关联。"
            val total = try { Math.addExact(totals[id] ?: 0, row.amountMinor) } catch (_: ArithmeticException) { return "累计退款金额超出范围。" }
            if (total > original.amountMinor) return "累计退款不能超过原支出金额。"
            totals[id] = total
        }
        return null
    }
}
