package io.qingledger.domain.model

data class CaptureSettings(val enabled: Boolean = false, val generation: Long = 0, val sources: Set<String> = emptySet())
data class CapturedNotification(
    val sourcePackage: String, val profileKey: String, val sessionKey: String,
    val digest: String, val receivedAt: Long, val versionCode: Long?, val generation: Long,
)
data class NotificationProposal(val amountMinor: Long?, val kind: TransactionKind?, val reason: String, val accountName: String? = null)
data class PendingCandidate(
    val id: String, val sourcePackage: String, val versionCode: Long?, val receivedAt: Long,
    val amountMinor: Long?, val kind: TransactionKind?, val reason: String,
    val state: String, val transactionId: String?, val relatedTransactionId: String?,
    val merchant: String? = null, val accountName: String? = null,
)

object NotificationRules {
    fun propose(text: String): NotificationProposal? {
        val content = text.take(8192)
        if (Regex("验证码|登录|余额提醒|可用额度|优惠券|满减|领券|支付失败|付款失败|交易失败|支付中|处理中|等待支付|已取消").containsMatchIn(content)) return null
        if (Regex("确认付款|确认支付|退款申请|退款失败|退款处理中|退款待领取").containsMatchIn(content)) return null
        if (!Regex("支付|付款|扣款|支出|收款|到账|收入|退款|转账|充值|提现|还款").containsMatchIn(content)) return null
        val numbers = Regex("[¥￥]\\s*([0-9]{1,12}(?:\\.[0-9]{1,2})?)(?![0-9.])|(?<![0-9.])([0-9]{1,12}(?:\\.[0-9]{1,2})?)\\s*元")
            .findAll(content).mapNotNull { LedgerRules.parseAmount(it.groups[1]?.value ?: it.groups[2]!!.value) }.distinct().toList()
        val expense = Regex("支付|付款|扣款|支出").containsMatchIn(content)
        val income = Regex("收款|到账|收入").containsMatchIn(content)
        val refund = Regex("退款成功|退款已到账|退款到账|你收到一笔\\s*[0-9]+(?:\\.[0-9]{1,2})?\\s*元退款").containsMatchIn(content)
        val review = when {
            Regex("美元|港币|USD|HKD|EUR", RegexOption.IGNORE_CASE).containsMatchIn(content) -> "UNSUPPORTED_CURRENCY"
            content.contains("退款") && !refund -> "REFUND_REVIEW"
            Regex("转账|充值|提现|还款").containsMatchIn(content) -> "TRANSFER_REVIEW"
            numbers.size > 1 -> "AMBIGUOUS_AMOUNT"
            numbers.isEmpty() -> "MISSING_AMOUNT"
            !refund && expense == income -> "AMBIGUOUS_DIRECTION"
            else -> "UNVERIFIED_TEMPLATE"
        }
        val kind = if (review in setOf("REFUND_REVIEW", "TRANSFER_REVIEW", "UNSUPPORTED_CURRENCY")) null
            else if (refund) TransactionKind.REFUND
            else if (expense == income) null
            else if (expense) TransactionKind.EXPENSE else TransactionKind.INCOME
        return NotificationProposal(numbers.singleOrNull(), kind, review)
    }
}
