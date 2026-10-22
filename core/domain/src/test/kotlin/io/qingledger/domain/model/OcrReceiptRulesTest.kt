package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class OcrReceiptRulesTest {
    @Test fun receiptMerchantComesOnlyFromUniqueCentralLineBeforeAmount() {
        val amount = OcrReceiptLine("¥28.00", 300, 550, 700, 650)
        val result = OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("测试商户", 420), amount, line("优惠券", 1000)), 2000)
        assertEquals("测试商户", result.merchant)
        assertNull(OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("测试商户", 380), line("另一说明", 450), amount), 2000).merchant)
    }
    @Test fun onlyAdaptedWechatReceiptActivityUsesOcrNotChatOrImageViewer() {
        assertTrue(OcrReceiptRules.supportsPage("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"))
        assertTrue(OcrReceiptRules.supportsPage("com.tencent.mm", null))
        assertFalse(OcrReceiptRules.supportsPage("com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI"))
        assertFalse(OcrReceiptRules.supportsPage("com.tencent.mm", "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"))
        assertFalse(OcrReceiptRules.supportsPage("other.app", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"))
    }
    private fun line(text: String, y: Int, x: Int = 250, right: Int = 750) = OcrReceiptLine(text, x, y, right, y + 50)
    @Test fun paymentReceiptIgnoresLowerCouponPanel() {
        val result = OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("测试商户", 450), line("¥28.00", 550), line("你有一个1元满减券待领取", 950), line("领取后再邀1人变为4元满减", 1050)), 2000)
        assertEquals("RECOGNIZED", result.reason)
        assertEquals(2800L, AccessibilityRules.propose(result.receiptText!!)?.amountMinor)
        assertFalse(result.receiptText!!.contains("满减"))
    }
    @Test fun separatesCurrencyAndAmountOnSameRowButNeverUsesBareUnlabelledNumber() {
        assertEquals("RECOGNIZED", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("￥", 500, 250, 300), line("28.00", 500, 320, 650)), 2000).reason)
        assertEquals("MISSING_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("28.00", 500)), 2000).reason)
    }
    @Test fun omittedCurrencyNeedsUniqueDominantAmountAlignedBelowSuccess() {
        val large = OcrReceiptLine("28.00", 300, 500, 700, 620)
        assertEquals("支付成功\n¥28.00", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), large), 2000).receiptText)
        assertEquals("MISSING_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), large.copy(left = 900, right = 1300)), 2000).reason)
        assertEquals("AMBIGUOUS_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), large, large.copy(top = 650, bottom = 770)), 2000).reason)
    }
    @Test fun ambiguousAmountsAndPendingOrHistoricalPagesNeverBook() {
        assertEquals("AMBIGUOUS_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("¥28.00", 500), line("¥29.00", 650)), 2000).reason)
        assertEquals("PAYMENT_IN_PROGRESS", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("¥28.00", 500), line("确认付款", 1500)), 2000).reason)
        assertEquals("HISTORY_PAGE", OcrReceiptRules.evaluate(listOf(line("交易记录", 100), line("支付成功", 300), line("¥28.00", 500)), 2000).reason)
    }
    @Test fun promotionalSuccessWordsAndAmountsOutsideReceiptRegionAreRejected() {
        assertEquals("NO_SUCCESS_STATE", OcrReceiptRules.evaluate(listOf(line("支付成功得绿色能量5g", 300), line("¥28.00", 500)), 2000).reason)
        assertEquals("MISSING_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("优惠券", 450), line("¥28.00", 500)), 2000).reason)
        assertEquals("MISSING_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("¥28.00", 1700)), 2000).reason)
    }
    @Test fun successIconDecorationIsAllowedButWordsAndOrdinaryNumbersAreNot() {
        assertEquals("支付成功\n¥28.00", OcrReceiptRules.evaluate(listOf(line("✓支付成功", 300), line("¥28.00", 500)), 2000).receiptText)
        assertEquals("RECOGNIZED", OcrReceiptRules.evaluate(listOf(line("◎ 支付成功", 300), line("¥28.00", 500)), 2000).reason)
        assertEquals("NO_SUCCESS_STATE", OcrReceiptRules.evaluate(listOf(line("微信支付成功", 300), line("¥28.00", 500)), 2000).reason)
        assertEquals("NO_SUCCESS_STATE", OcrReceiptRules.evaluate(listOf(line("1支付成功", 300), line("¥28.00", 500)), 2000).reason)
    }
    @Test fun singleUppercaseIconMisreadNeedsDominantAlignedReceiptAmount() {
        assertEquals("支付成功\n¥28.00", OcrReceiptRules.evaluate(listOf(line("D支付成功", 300), OcrReceiptLine("28.00", 300, 500, 700, 620)), 2000).receiptText)
        assertEquals("NO_SUCCESS_STATE", OcrReceiptRules.evaluate(listOf(line("D支付成功", 300), line("¥28.00", 500)), 2000).reason)
        assertEquals("NO_SUCCESS_STATE", OcrReceiptRules.evaluate(listOf(line("VIP支付成功", 300), OcrReceiptLine("28.00", 300, 500, 700, 620)), 2000).reason)
    }
    @Test fun foreignCurrenciesAreNeverAssumedToBeYuanWhenSymbolIsMissing() {
        assertEquals("UNSUPPORTED_CURRENCY", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("USD", 400), OcrReceiptLine("28.00", 300, 500, 700, 620)), 2000).reason)
    }
    @Test fun duplicateSuccessStatesAndNonPositiveAmountsAreRejected() {
        assertEquals("AMBIGUOUS_DIRECTION", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("付款成功", 400), line("¥28.00", 500)), 2000).reason)
        assertEquals("MISSING_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("¥0.00", 500)), 2000).reason)
    }
    @Test fun acceptsModelYCurrencyConfusionOnlyForWholeAmountInsideVerifiedReceipt() {
        assertEquals("支付成功\n¥28.00", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("Y28.00", 500)), 2000).receiptText)
        assertEquals("MISSING_AMOUNT", OcrReceiptRules.evaluate(listOf(line("支付成功", 300), line("优惠Y28.00", 500)), 2000).reason)
        assertEquals("NO_SUCCESS_STATE", OcrReceiptRules.evaluate(listOf(line("Y28.00", 500)), 2000).reason)
    }
}
