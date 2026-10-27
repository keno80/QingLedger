package io.qingledger.domain.model

data class OcrPaymentContext(val amountMinor: Long, val merchant: String?, val accountName: String?, val originalAmountMinor: Long? = null, val discountMinor: Long? = null)
object OcrPaymentPageRules {
    data class Result(val reason: String, val context: OcrPaymentContext? = null)
    fun evaluate(lines: List<OcrReceiptLine>, imageWidth: Int, imageHeight: Int): Result {
        val ordered = lines.take(256).filter { it.top >= 0 && it.bottom > it.top && it.bottom <= imageHeight && it.left >= 0 && it.right > it.left && it.right <= imageWidth }
            .map { it.copy(text = it.text.take(512).replace(Regex("\\s|\\u200b"), "")) }.sortedBy { it.top }
        val all = ordered.joinToString("\n") { it.text }
        if (PaymentCapturePhaseRules.isFailure(all)) return Result("PAYMENT_CONTEXT_CLEARED")
        if (all.contains("选择付款方式")) return Result("PAYMENT_METHOD_LIST")
        val labels = ordered.filter { Regex("^付款方式(?:更改)?[\\p{P}\\p{S}]*$").matches(it.text) }
        if (labels.size != 1 || ordered.none { it.text in setOf("支付", "确认支付", "确认付款", "使用密码") }) return Result("NOT_CONFIRMATION")
        if (Regex("[$€£]|USD|HKD|EUR|美元|港币").containsMatchIn(all)) return Result("UNSUPPORTED_CURRENCY")
        val label = labels.single()
        val money = Regex("^[¥￥Y]?([0-9]{1,12}\\.[0-9]{2})$")
        val secondaryLabels = ordered.filter { it.text in setOf("原价", "优惠", "优惠金额", "立减") }
        val amounts = ordered.filter { it.bottom <= label.top && it.top >= imageHeight * 15 / 100 &&
            kotlin.math.abs((it.left + it.right) / 2 - imageWidth / 2) <= imageWidth / 4 &&
            (it.text.startsWith("¥") || it.text.startsWith("￥") || it.text.startsWith("Y") || (it.bottom - it.top) * 2 >= (label.bottom - label.top) * 3) &&
            secondaryLabels.none { field -> field.right <= it.left && field.top < it.bottom && it.top < field.bottom }
        }.mapNotNull { line -> money.matchEntire(line.text)?.groupValues?.get(1)?.let(LedgerRules::parseAmount)?.let { line to it } }
        if (amounts.size != 1) return Result(if (amounts.isEmpty()) "MISSING_AMOUNT" else "AMBIGUOUS_AMOUNT")
        val (amountLine, amountMinor) = amounts.single()
        val merchant = OcrMerchantRules.pick(ordered, maxOf(0, amountLine.top - imageHeight / 4), amountLine.top, amountLine)
        val boundary = ordered.firstOrNull { it.top >= label.bottom && Regex("^使用|优惠|推荐|立减|全部银行卡|分期").containsMatchIn(it.text) }?.top ?: imageHeight
        val accountLines = ordered.filter { it.top >= label.bottom && it.bottom <= boundary }
        val candidates = accountLines.mapNotNull { line -> account(line, accountLines) }.distinct()
        val account = candidates.singleOrNull()
        val prices = PaymentDiscountRules.fromOcr(ordered, amountMinor, amountLine.bottom, label.top)
        return Result(if (account == null) "PAYMENT_CONTEXT_PARTIAL" else "PAYMENT_CONTEXT_CAPTURED", OcrPaymentContext(amountMinor, merchant, account, prices.originalAmountMinor, prices.discountMinor))
    }

    private fun account(line: OcrReceiptLine, rows: List<OcrReceiptLine>): String? {
        fun tail(value: String) = value.replace('Ø', '0').replace('O', '0').replace('o', '0')
        val text = line.text.trimStart { !it.isLetterOrDigit() }
        if (Regex("使用|优惠|推荐|分期").containsMatchIn(text)) return null
        val wallet = Regex("^(微信零钱|微信零钱通|零钱通|零钱)(?:[（(](?:剩余|余额).*[）)])?$").matchEntire(text)
        if (wallet != null) return PaymentAccountRules.canonicalName(wallet.groupValues[1])
        val inline = Regex("[（(]([0-9ØOo]{4})[）)]").replace(text) { "（${tail(it.groupValues[1])}）" }
        PaymentAccountRules.canonicalName(inline)?.let { return it }
        val bank = Regex("^([\\p{IsHan}A-Za-z]{2,24}银行)(?:[\\p{IsHan}]{1,6}卡)?(?:（([0-9]{4})）)?$").matchEntire(inline) ?: return null
        val bankName = bank.groupValues[1]
        if (bank.groupValues[2].isNotBlank()) return PaymentAccountRules.canonicalName("$bankName（${bank.groupValues[2]}）")
        val tails = rows.filter { it.top >= line.bottom && it.top - line.bottom <= (line.bottom - line.top) * 2 }
            .filterNot { Regex("[$€£¥￥]").containsMatchIn(it.text) }
            .mapNotNull { Regex("^[\\p{P}\\p{S}xX]*([0-9ØOo]{4})$").matchEntire(it.text)?.groupValues?.get(1)?.let(::tail) }.distinct()
        val explicitName = text.takeIf { it.endsWith("储蓄卡") || it.endsWith("信用卡") } ?: bankName
        return tails.singleOrNull()?.let { PaymentAccountRules.canonicalName("$explicitName（$it）") }
    }
}
