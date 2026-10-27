package io.qingledger.domain.model

class PaymentSessionCache {
    private data class Pending(val source: String, val generation: Long, val amount: Long, val account: String, val observedAt: Long)
    private var pending: Pending? = null
    fun observe(source: String, generation: Long, text: String, selected: List<String>, now: Long) {
        val content = text.take(8192)
        if (PaymentCapturePhaseRules.isAuthentication(content) && !PaymentCapturePhaseRules.isFailure(content)) return
        if (!Regex("确认付款|选择付款方式|付款方式").containsMatchIn(content) ||
            Regex("(?m)^(支付成功|付款成功|收款成功|到账成功|付款失败|支付失败|已取消)$").containsMatchIn(content)) {
            clear(); return
        }
        val amountText = PaymentAccountRules.transactionText(content).lines().filterNot { Regex("余额|剩余").containsMatchIn(it) }.joinToString("\n")
        val amounts = Regex("""[¥￥]\s*([0-9]{1,12}(?:\.[0-9]{1,2})?)(?![0-9.])|(?<![0-9.])([0-9]{1,12}(?:\.[0-9]{1,2})?)\s*元""")
            .findAll(amountText).mapNotNull { LedgerRules.parseAmount(it.groups[1]?.value ?: it.groups[2]!!.value) }.distinct().toList()
        val account = if (selected.isNotEmpty()) selected.map(PaymentAccountRules::canonicalName).distinct().singleOrNull()
            else PaymentAccountRules.extract(content, TransactionKind.EXPENSE)
        val amount = amounts.singleOrNull()
        pending = if (amount != null && account != null) Pending(source, generation, amount, account, now) else null
    }
    fun consume(source: String, generation: Long, success: NotificationProposal, now: Long): String? {
        val previous = pending; clear()
        return previous?.takeIf {
            it.source == source && it.generation == generation && it.amount == success.amountMinor &&
                success.kind == TransactionKind.EXPENSE && now - it.observedAt in 0..120000
        }?.account
    }
    fun clear() { pending = null }
}
