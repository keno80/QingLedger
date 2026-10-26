package io.qingledger.domain.model

data class PaymentDiscount(val originalAmountMinor: Long? = null, val discountMinor: Long? = null)

object PaymentDiscountRules {
    fun parseAmount(text: String): Long? = text.trim().takeIf { Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(it) }?.let {
        runCatching { java.math.BigDecimal(it).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { n -> n in 0..LedgerRules.MAX_AMOUNT }
    }
    fun validate(kind: TransactionKind, paid: Long, original: Long?, discount: Long?): String? = when {
        original == null && discount == null -> null
        kind != TransactionKind.EXPENSE -> "只有支出可以填写原价和优惠。"
        original != null && (original !in 1..LedgerRules.MAX_AMOUNT || original < paid) -> "原价不能小于实付金额或超出范围。"
        discount != null && discount !in 0..LedgerRules.MAX_AMOUNT -> "优惠金额不能为负数或超出范围。"
        original != null && discount != null && original - paid != discount -> "原价减去优惠必须等于实付金额。"
        else -> null
    }
    fun merge(paid: Long, first: PaymentDiscount, second: PaymentDiscount): PaymentDiscount {
        if (first.originalAmountMinor != null && second.originalAmountMinor != null && first.originalAmountMinor != second.originalAmountMinor ||
            first.discountMinor != null && second.discountMinor != null && first.discountMinor != second.discountMinor) return PaymentDiscount()
        val result = PaymentDiscount(first.originalAmountMinor ?: second.originalAmountMinor, first.discountMinor ?: second.discountMinor)
        return result.takeIf { validate(TransactionKind.EXPENSE, paid, it.originalAmountMinor, it.discountMinor) == null } ?: PaymentDiscount()
    }
    fun fromOcr(lines: List<OcrReceiptLine>, paid: Long, amountBottom: Int, fieldTop: Int): PaymentDiscount {
        val rows = lines.take(256).filter { it.top >= amountBottom && it.bottom <= fieldTop }
            .map { it.copy(text = it.text.take(512).replace(Regex("\\s+"), "")) }
        fun value(labels: Set<String>): Long? {
            val values = rows.filter { row -> labels.any { row.text.startsWith(it) } }.flatMap { label ->
                val sameRow = rows.filter { it.left >= label.right && it.top < label.bottom && label.top < it.bottom }
                (listOf(label) + sameRow).mapNotNull { row ->
                    if (Regex("最高|最多|可享|有机会|使用|推荐|领取|待领取|返现|积分|满|减至|预计|分期|免息|折|元起|余额|剩余|可用|赠送|[-−$€£%]").containsMatchIn(row.text)) return@mapNotNull null
                    val bare = row.text.replace(Regex("^(?:原价|优惠金额|优惠|立减)[:：]?"), "")
                    val explicitMoney = Regex("[¥￥Y][0-9]+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?元").containsMatchIn(row.text)
                    if (!explicitMoney && !Regex("[0-9]+(?:\\.[0-9]{1,2})?").matches(bare)) return@mapNotNull null
                    val numbers = Regex("(?<![0-9.])([0-9]{1,12}(?:\\.[0-9]{1,2})?)(?![0-9.])").findAll(row.text).toList()
                    if (numbers.size != 1) null else parseAmount(numbers.single().groupValues[1])
                }
            }.distinct()
            return values.singleOrNull()
        }
        val original = value(setOf("原价"))?.takeIf { it >= paid }
        val discount = value(setOf("优惠", "立减"))
        return merge(paid, PaymentDiscount(original, discount), PaymentDiscount())
    }
}
