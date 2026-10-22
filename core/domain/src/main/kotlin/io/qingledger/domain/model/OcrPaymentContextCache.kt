package io.qingledger.domain.model

class OcrPaymentContextCache {
    private data class Pending(val source: String, val generation: Long, val context: OcrPaymentContext, val at: Long)
    private var pending: Pending? = null
    fun observe(source: String, generation: Long, page: OcrPaymentPageRules.Result, now: Long) {
        if (page.reason == "PAYMENT_METHOD_LIST") {
            if (pending?.let { it.source == source && it.generation == generation && now - it.at in 0..120000 } != true) clear()
            return
        }
        pending = page.context?.takeIf { it.amountMinor in 1..LedgerRules.MAX_AMOUNT }?.let { Pending(source, generation, it, now) }
    }
    fun consume(source: String, generation: Long, success: NotificationProposal, merchant: String?, now: Long): OcrPaymentContext? {
        val previous = pending; clear()
        fun normalized(value: String) = value.replace(Regex("\\s+"), "").lowercase()
        return previous?.takeIf {
            it.source == source && it.generation == generation && now - it.at in 0..120000 &&
                success.kind == TransactionKind.EXPENSE && success.amountMinor == it.context.amountMinor &&
                (merchant == null || it.context.merchant == null || normalized(merchant) == normalized(it.context.merchant))
        }?.context
    }
    fun clear() { pending = null }
}
