package io.qingledger.domain.model

enum class CaptureMethod(val label: String) {
    ACCESSIBILITY("无障碍"), SHIZUKU("Shizuku + ADB"), ROOT("Root"),
    NOTIFICATION("通知（旧版）"),
}

object AccessibilityRules {
    fun propose(text: String): NotificationProposal? {
        val content = text.take(8192)
        if (Regex("选择付款方式|确认付款|确认支付|交易记录|账单列表|历史记录|聊天记录|转账|充值|提现|还款").containsMatchIn(content)) return null
        if (!Regex("(?m)^(支付成功|付款成功|收款成功|到账成功|退款成功)[ \\t]*$").containsMatchIn(content)) return null
        val proposal = NotificationRules.propose(PaymentAccountRules.transactionText(content)) ?: return null
        if (proposal.reason != "UNVERIFIED_TEMPLATE" || proposal.amountMinor == null || proposal.kind == null) return null
        return proposal.copy(accountName = PaymentAccountRules.extract(content, proposal.kind))
    }
}
