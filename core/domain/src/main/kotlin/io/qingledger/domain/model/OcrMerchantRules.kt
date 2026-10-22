package io.qingledger.domain.model

internal object OcrMerchantRules {
    fun pick(lines: List<OcrReceiptLine>, start: Int, end: Int, amount: OcrReceiptLine): String? {
        val candidates = lines.filter { it.top >= start && it.bottom <= end &&
            kotlin.math.abs((it.left + it.right) / 2 - (amount.left + amount.right) / 2) <= maxOf(amount.right - amount.left, it.right - it.left) / 2
        }.mapNotNull { line ->
            val text = line.text.trim().replace(Regex("\\s+"), " ")
            text.takeIf { it.length in 2..64 && it.any(Char::isLetter) &&
                Regex("^[\\p{L}0-9·•&（）()_ *-]+$").matches(it) &&
                !Regex("支付|付款|收款|密码|优惠|推荐|领取|红包|余额|剩余|更改|银行卡|储蓄卡|信用卡|账单|返回商户").containsMatchIn(it) }
        }.distinct()
        return candidates.singleOrNull()
    }
}
