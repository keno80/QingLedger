package io.qingledger.domain.model

/** Only explicit account fields are used; option lists and card balances are not accounts. */
object PaymentAccountRules {
    private val bankOnly = Regex("^([\\p{IsHan}A-Za-z]{2,24}银行)[（(](\\d{4})[）)]$")
    private val fields = Regex("""(?m)^(付款方式|入账账户|到账账户)[ \t]*(?:[:：][ \t]*(?:\r?\n)?|\r?\n)([^\r\n]+)$""")
    fun transactionText(text: String): String = fields.replace(text, "")
    fun extract(text: String, kind: TransactionKind): String? {
        val values = fields.findAll(text).filter {
            if (kind == TransactionKind.EXPENSE) it.groupValues[1] == "付款方式"
            else if (kind == TransactionKind.INCOME) it.groupValues[1] != "付款方式"
            else false
        }.map { canonicalName(it.groupValues[2]) }.distinct().toList()
        return values.singleOrNull()
    }
    fun canonicalName(raw: String): String? {
        val value = raw.replace(Regex("\\s+"), "")
        bankOnly.matchEntire(value)?.let { return "${it.groupValues[1]}（${it.groupValues[2]}）" }
        return when (value) {
            "余额", "支付宝余额" -> "支付宝余额"
            "余额宝" -> "余额宝"
            "零钱", "微信零钱" -> "微信零钱"
            "零钱通", "微信零钱通" -> "微信零钱通"
            else -> {
                val match = Regex("""^([\p{IsHan}A-Za-z]{2,24}(?:储蓄卡|信用卡))[（(](\d{4})[）)]$""").matchEntire(value) ?: return null
                val name = match.groupValues[1]
                if (!name.contains("银行")) return null
                "$name（${match.groupValues[2]}）"
            }
        }
    }
    fun unspecifiedBankType(raw: String): Boolean = bankOnly.matches(raw.replace(Regex("\\s+"), ""))
    fun bankIdentity(raw: String): Pair<String, String>? {
        val value = canonicalName(raw) ?: return null
        val match = Regex("^([\\p{IsHan}A-Za-z]{2,24}银行)(?:储蓄卡|信用卡)?（(\\d{4})）$").matchEntire(value) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }
}
