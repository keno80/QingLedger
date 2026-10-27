package io.qingledger.domain.model

data class OcrPaymentCacheState(val source: String, val generation: Long, val context: OcrPaymentContext, val at: Long)
data class OcrPaymentConsumption(val context: OcrPaymentContext?, val reason: String)
class OcrPaymentContextCache {
    private data class Pending(val source: String, val generation: Long, val context: OcrPaymentContext, val at: Long)
    private var pending: Pending? = null
    private var clearReason = "PAYMENT_CONTEXT_MISSING"
    fun snapshot(): OcrPaymentCacheState? = pending?.let { OcrPaymentCacheState(it.source, it.generation, it.context, it.at) }
    fun restore(saved: OcrPaymentCacheState?, now: Long) {
        clearReason = if (saved != null && now - saved.at !in 0..120000) "PAYMENT_CONTEXT_EXPIRED" else "PAYMENT_CONTEXT_MISSING"
        pending = saved?.takeIf { now - it.at in 0..120000 && it.context.amountMinor in 1..LedgerRules.MAX_AMOUNT &&
            it.context.merchant.orEmpty().length <= 128 && it.context.accountName.orEmpty().length <= 64 &&
            PaymentDiscountRules.validate(TransactionKind.EXPENSE, it.context.amountMinor, it.context.originalAmountMinor, it.context.discountMinor) == null
        }?.let { Pending(it.source, it.generation, it.context, it.at) }
    }
    fun consumeDetailed(source: String, generation: Long, success: NotificationProposal, merchant: String?, now: Long): OcrPaymentConsumption {
        val previous = pending; val previousReason = clearReason; clear()
        fun normalized(value: String) = value.replace(Regex("\\s+"), "").lowercase()
        val reason = when {
            previous == null -> previousReason
            now - previous.at !in 0..120000 -> "PAYMENT_CONTEXT_EXPIRED"
            previous.source != source || previous.generation != generation || success.kind != TransactionKind.EXPENSE ||
                success.amountMinor != previous.context.amountMinor || (merchant != null && previous.context.merchant != null && normalized(merchant) != normalized(previous.context.merchant)) -> "PAYMENT_CONTEXT_CONFLICT"
            previous.context.accountName == null -> "PAYMENT_ACCOUNT_NOT_READ"
            else -> "PAYMENT_CONTEXT_MATCHED"
        }
        return OcrPaymentConsumption(previous?.context?.takeIf { reason in setOf("PAYMENT_CONTEXT_MATCHED", "PAYMENT_ACCOUNT_NOT_READ") }, reason)
    }
    fun observe(source: String, generation: Long, page: OcrPaymentPageRules.Result, now: Long) {
        if (page.reason == "PAYMENT_METHOD_LIST") {
            if (pending?.let { it.source == source && it.generation == generation && now - it.at in 0..120000 } != true) clear()
            return
        }
        pending = page.context?.takeIf { it.amountMinor in 1..LedgerRules.MAX_AMOUNT }?.let { Pending(source, generation, it, now) }
    }
    fun consume(source: String, generation: Long, success: NotificationProposal, merchant: String?, now: Long): OcrPaymentContext? {
        return consumeDetailed(source, generation, success, merchant, now).context
    }
    fun clear(reason: String = "PAYMENT_CONTEXT_MISSING") { pending = null; clearReason = reason }
}
