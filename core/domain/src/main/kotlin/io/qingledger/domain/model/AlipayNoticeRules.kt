package io.qingledger.domain.model

/** Settled Alipay notices only; marketing, summaries and refund applications are not receipts. */
object AlipayNoticeRules {
    fun propose(text: String): NotificationProposal? {
        val content = text.take(8192)
        val amount = "[0-9]{1,12}(?:\\.[0-9]{1,2})?"
        if (!Regex("(?m)^(?:你有一笔${amount}元的(?:支出|收入)|你收到一笔${amount}元退款)(?:[，,。！!]|$)").containsMatchIn(content)) return null
        return NotificationRules.propose(content)
    }
}
