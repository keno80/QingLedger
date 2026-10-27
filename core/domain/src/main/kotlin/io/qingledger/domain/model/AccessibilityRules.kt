package io.qingledger.domain.model

enum class CaptureMethod(val label: String) {
    ACCESSIBILITY("无障碍"), SHIZUKU("Shizuku + ADB"), ROOT("Root"),
    NOTIFICATION("通知（旧版）"),
}

object AccessibilityRules {
    data class Decision(val proposal: NotificationProposal?, val reason: String)
    private val success = Regex("^(支付成功|付款成功|收款成功|到账成功|退款成功)(?:[ \\t:：]*[¥￥]?[ \\t]*([0-9]{1,12}\\.[0-9]{2})[ \\t]*元?)?[ \\t]*$")
    private val decimal = Regex("^[0-9]{1,12}\\.[0-9]{2}$")
    fun propose(text: String): NotificationProposal? = evaluate(text).proposal
    fun evaluate(text: String): Decision {
        val content = text.take(8192).replace('\u00a0', ' ').replace("\u200b", "")
        fun reject(reason: String) = Decision(null, reason)
        if (content.isBlank()) return reject("PAGE_EMPTY")
        WeChatOutgoingRules.propose(content)?.let { return Decision(it, "RECOGNIZED") }
        if (Regex("选择付款方式|确认付款|确认支付").containsMatchIn(content)) return reject("PAYMENT_IN_PROGRESS")
        if (Regex("交易记录|账单列表|历史记录|聊天记录").containsMatchIn(content)) return reject("HISTORY_PAGE")
        if (Regex("转账|充值|提现|还款").containsMatchIn(content)) return reject("TRANSFER_REVIEW")
        val lines = PaymentAccountRules.transactionText(content).lines().map(String::trim)
        val states = lines.mapIndexedNotNull { index, line -> success.matchEntire(line)?.let { index to it } }
        if (states.isEmpty()) return reject("NO_SUCCESS_STATE")
        val directions = states.map { (_, match) -> when (match.groupValues[1]) { "收款成功", "到账成功" -> TransactionKind.INCOME; "退款成功" -> TransactionKind.REFUND; else -> TransactionKind.EXPENSE } }.distinct()
        if (directions.size != 1) return reject("AMBIGUOUS_DIRECTION")
        // Bare decimals are accepted only beside an explicit success state. Never
        // choose a number from a balance, bank tail, unrelated section or list.
        val bare = lines.withIndex().filter { decimal.matches(it.value) }
        if (bare.size > 1) return reject("AMBIGUOUS_AMOUNT")
        val normalized = lines.mapIndexed { index, line ->
            val match = success.matchEntire(line)
            when {
                match != null && match.groupValues[2].isNotEmpty() -> match.groupValues[1] + "\n¥" + match.groupValues[2]
                bare.size == 1 && bare.single().index == index && states.any { kotlin.math.abs(it.first - index) <= 2 } -> "¥$line"
                else -> line
            }
        }.joinToString("\n")
        val proposal = NotificationRules.propose(normalized) ?: return reject("UNSETTLED_OR_UNSUPPORTED")
        if (proposal.reason != "UNVERIFIED_TEMPLATE" || proposal.amountMinor == null || proposal.kind == null) return reject(proposal.reason)
        if (proposal.kind != directions.single()) return reject("AMBIGUOUS_DIRECTION")
        return Decision(proposal.copy(accountName = PaymentAccountRules.extract(content, proposal.kind)), "RECOGNIZED")
    }
}
