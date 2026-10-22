package io.qingledger.domain.model

data class OcrReceiptLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
object OcrReceiptRules {
    // Some Lite pages expose only FrameLayout events, with no Activity identity.
    // In that case the visual receipt checks below remain mandatory.
    fun supportsPage(source: String, activity: String?) = source == "com.tencent.mm" &&
        (activity == null || activity == "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI")
    data class Result(val reason: String, val receiptText: String? = null, val merchant: String? = null)
    // Chinese ML Kit can read the yuan glyph as Y. Normalize only a complete
    // decimal amount line inside the verified receipt region, never arbitrary text.
    private val amount = Regex("^[¥￥Y]([0-9]{1,12}\\.[0-9]{2})$")
    private val decimal = Regex("^[0-9]{1,12}\\.[0-9]{2}$")
    private val decoratedState = Regex("^[\\p{P}\\p{S}]*[A-Z]?(支付成功|付款成功)[\\p{P}\\p{S}]*$")
    private val letterIconState = Regex("^[\\p{P}\\p{S}]*[A-Z](支付成功|付款成功)[\\p{P}\\p{S}]*$")
    fun evaluate(lines: List<OcrReceiptLine>, imageHeight: Int): Result {
        val letterIconPositions = mutableSetOf<Int>()
        val ordered = lines.take(256).filter { it.top >= 0 && it.bottom > it.top && it.bottom <= imageHeight && it.right > it.left }
            .map {
                val text = it.text.take(512).replace(Regex("\\s|\\u200b"), "")
                if (letterIconState.matches(text)) letterIconPositions += it.top
                it.copy(text = decoratedState.matchEntire(text)?.groupValues?.get(1) ?: text)
            }.sortedWith(compareBy({ it.top }, { it.left }))
        val all = ordered.joinToString("\n") { it.text }
        if (Regex("[$€£]|USD|HKD|JPY|EUR|美元|港币|日元").containsMatchIn(all)) return Result("UNSUPPORTED_CURRENCY")
        if (Regex("确认付款|确认支付|选择付款方式|使用密码|支付密码|待付款|支付中|支付失败|取消支付").containsMatchIn(all)) return Result("PAYMENT_IN_PROGRESS")
        if (Regex("交易记录|账单列表|历史记录|聊天记录").containsMatchIn(all)) return Result("HISTORY_PAGE")
        if (Regex("转账|充值|提现|还款").containsMatchIn(all)) return Result("TRANSFER_REVIEW")
        val states = ordered.filter { it.text == "支付成功" || it.text == "付款成功" }
        if (states.isEmpty()) return Result("NO_SUCCESS_STATE")
        if (states.size != 1) return Result("AMBIGUOUS_DIRECTION")
        val state = states.single()
        if (state.top > imageHeight * 35 / 100) return Result("NO_SUCCESS_STATE")
        val advertTop = ordered.firstOrNull { it.top > state.bottom && Regex("优惠|满减|领取|红包|领券|推荐|广告").containsMatchIn(it.text) }?.top ?: imageHeight
        val end = minOf(advertTop, state.bottom + imageHeight * 32 / 100)
        val region = ordered.filter { it.top >= state.bottom && it.bottom <= end }
        fun dominantAligned(line: OcrReceiptLine): Boolean =
            kotlin.math.abs((line.left + line.right) / 2 - (state.left + state.right) / 2) <= maxOf(line.right - line.left, state.right - state.left) * 35 / 100 &&
                (line.bottom - line.top) * 100 >= (state.bottom - state.top) * 125
        // A single uppercase glyph may be the WeChat success icon, but accepting
        // that requires the visually dominant, aligned receipt amount as well.
        if (state.top in letterIconPositions && region.none { (decimal.matches(it.text) || amount.matches(it.text)) && dominantAligned(it) }) return Result("NO_SUCCESS_STATE")
        val candidates = region.mapNotNull { amount.matchEntire(it.text)?.groupValues?.get(1) }.toMutableList()
        for (currency in region.filter { it.text == "¥" || it.text == "￥" || it.text == "Y" }) {
            region.filter { decimal.matches(it.text) && it.left >= currency.right && it.left - currency.right <= (it.bottom - it.top) * 2 && minOf(it.bottom, currency.bottom) - maxOf(it.top, currency.top) >= minOf(it.bottom - it.top, currency.bottom - currency.top) / 2 }
                .forEach { candidates += it.text }
        }
        val currencies = region.filter { it.text == "¥" || it.text == "￥" || it.text == "Y" }
        for (bare in region.filter { decimal.matches(it.text) }) {
            val paired = currencies.any { currency -> bare.left >= currency.right && bare.left - currency.right <= (bare.bottom - bare.top) * 2 && minOf(bare.bottom, currency.bottom) - maxOf(bare.top, currency.top) >= minOf(bare.bottom - bare.top, currency.bottom - currency.top) / 2 }
            if (!paired && dominantAligned(bare)) candidates += bare.text
        }
        if (candidates.size > 1) return Result("AMBIGUOUS_AMOUNT")
        val value = candidates.singleOrNull()?.takeIf { it.toBigDecimal() > java.math.BigDecimal.ZERO } ?: return Result("MISSING_AMOUNT")
        // Only the verified success/amount pair enters existing rules; discard promotional text.
        val amountLine = region.firstOrNull { it.text == value || amount.matchEntire(it.text)?.groupValues?.get(1) == value }
        val merchant = amountLine?.let { OcrMerchantRules.pick(ordered, state.bottom, it.top, it) }
        return Result("RECOGNIZED", "${state.text}\n¥$value", merchant)
    }
}
