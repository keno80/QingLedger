package io.qingledger.domain.model

object WeChatOutgoingRules {
    val sentStates = setOf("转账成功", "转账已发出", "红包已发出", "红包发送成功", "发送红包成功")
    fun counterparty(text: String): String? {
        val value = text.replace(Regex("\\s+"), "")
        fun name(pattern: String) = Regex(pattern).matchEntire(value)?.groupValues?.get(1)?.takeIf { it.length in 1..64 && it.none(Character::isISOControl) }
        for (pattern in listOf("^待(.+)确认收款$", "^向(.+)转账$", "^给(.+)转账$", "^转账给(.+)$")) name(pattern)?.let { return "转账给$it" }
        for (pattern in listOf("^红包发给(.+)$", "^给(.+)发红包$", "^向(.+)发红包$")) name(pattern)?.let { return "红包发给$it" }
        return null
    }
    fun propose(text: String): NotificationProposal? {
        val content = text.take(8192)
        val lines = content.lines().map { it.trim() }
        if (lines.take(4).any { it in setOf("转账详情", "红包详情", "交易详情", "账单详情") } ||
            Regex("聊天记录|历史记录|交易记录|支付失败|付款失败|发送失败|未发送|退款|退还|充值|提现|还款|[$€£]|USD|HKD").containsMatchIn(content) ||
            lines.any { it in setOf("发送消息", "发送", "按住说话", "确认付款", "确认支付", "选择付款方式") }) return null
        val states = lines.withIndex().filter { it.value in sentStates || it.value == "支付成功" || it.value == "付款成功" }
        if (states.size != 1) return null
        val parties = lines.mapNotNull(::counterparty).distinct()
        if (parties.size > 1) return null
        val state = states.single()
        if (state.value !in sentStates && parties.isEmpty()) return null
        if (state.value == "转账成功" && parties.none { it.startsWith("转账给") }) return null
        val amounts = lines.mapIndexedNotNull { index, line ->
            when {
                Regex("^[¥￥][0-9]+(?:\\.[0-9]{1,2})?元?$").matches(line) -> LedgerRules.parseAmount(line.removePrefix("¥").removePrefix("￥").removeSuffix("元"))
                index > 0 && lines[index - 1] in setOf("¥", "￥") -> LedgerRules.parseAmount(line.removeSuffix("元"))
                kotlin.math.abs(index - state.index) <= 2 -> LedgerRules.parseAmount(line)
                else -> null
            }
        }.distinct()
        val amount = amounts.singleOrNull() ?: return null
        val merchant = parties.singleOrNull() ?: if (state.value.contains("红包")) "微信红包" else "微信转账"
        return NotificationProposal(amount, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE", PaymentAccountRules.extract(content, TransactionKind.EXPENSE), merchant)
    }
}
