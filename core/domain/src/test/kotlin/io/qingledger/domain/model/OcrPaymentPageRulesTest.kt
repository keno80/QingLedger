package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class OcrPaymentPageRulesTest {
    private fun line(text: String, y: Int, left: Int = 300, right: Int = 700, height: Int = 45) = OcrReceiptLine(text, left, y, right, y + height)
    private val header = listOf(line("使用密码", 250, 750, 980), line("测试商户", 400), line("¥28.00", 500, height = 100), line("付款方式", 800, 50, 250))
    private fun parse(rows: List<OcrReceiptLine>) = OcrPaymentPageRules.evaluate(rows, 1000, 2000)
    @Test fun splitBankAndMaskedTailBeatRecommendedCreditCard() {
        val result = parse(header + listOf(line("农业银行储蓄卡", 950), line("**** **** 0230", 1020), line("使用建设银行信用卡(5678)", 1200), line("优惠0.40元", 1270), line("支付", 1700)))
        assertEquals("PAYMENT_CONTEXT_CAPTURED", result.reason)
        assertEquals(OcrPaymentContext(2800, "测试商户", "农业银行储蓄卡（0230）"), result.context)
    }
    @Test fun walletBalanceDoesNotBecomePaymentAmount() {
        val result = parse(header + listOf(line("零钱(剩余¥99.00)", 950), line("支付", 1700)))
        assertEquals(OcrPaymentContext(2800, "测试商户", "微信零钱"), result.context)
    }
    @Test fun explicitlyLabeledOriginalPriceAndDiscountDoNotReplaceActualAmount() {
        val result = parse(header + listOf(line("原价", 650, 50, 180), line("¥30.00", 650, 560, 740),
            line("优惠", 720, 50, 180), line("¥2.00", 720, 560, 740), line("测试银行信用卡(1234)", 950), line("支付", 1700)))
        assertEquals("PAYMENT_CONTEXT_CAPTURED", result.reason)
        assertEquals(2800L, result.context?.amountMinor)
        assertEquals("测试银行信用卡（1234）", result.context?.accountName)
    }
    @Test fun unlabeledExtraAmountRemainsAmbiguous() {
        val result = parse(header + listOf(line("¥30.00", 650, 500, 780), line("测试银行信用卡(1234)", 950), line("支付", 1700)))
        assertEquals("AMBIGUOUS_AMOUNT", result.reason)
        assertNull(result.context)
    }
    @Test fun pickerIsNotAConfirmationEvenWhenItContainsSelectedAccount() {
        assertEquals("PAYMENT_METHOD_LIST", parse(listOf(line("选择付款方式", 100), line("农业银行储蓄卡(0230)", 950))).reason)
    }
    @Test fun ambiguousPrimaryAccountsAndUnknownTailRemainUnknown() {
        val result = parse(header + listOf(line("农业银行储蓄卡(0230)", 950), line("民生银行储蓄卡(5678)", 1100), line("支付", 1700)))
        assertEquals("PAYMENT_CONTEXT_PARTIAL", result.reason)
        assertNull(result.context?.accountName)
        assertNull(parse(header + listOf(line("农业银行储蓄卡", 950), line("****", 1020), line("支付", 1700))).context?.accountName)
    }
    @Test fun noAmountOrNoConfirmationControlsNeverCreatesContext() {
        assertNull(parse(header.filterNot { it.text.startsWith("¥") } + line("微信零钱", 950) + line("支付", 1700)).context)
        assertNull(parse(header.filterNot { it.text == "使用密码" } + line("微信零钱", 950)).context)
        assertNull(parse(header + line("已取消", 1700)).context)
    }
    @Test fun largeBareAmountAndSlashedZerosAreHandledOnlyInBankTail() {
        val result = parse(header.map { if (it.text == "¥28.00") it.copy(text = "28.00") else it } + listOf(line("农业银行储蓄卡", 950), line("**** Ø23Ø", 1020), line("支付", 1700)))
        assertEquals("农业银行储蓄卡（0230）", result.context?.accountName)
    }
    @Test fun unreadableCardSubtypeKeepsOnlyVerifiedBankAndTail() {
        val result = parse(header + listOf(line(")农业银行储营卡", 950), line("**** 0230", 1020), line("支付", 1700)))
        assertEquals("农业银行（0230）", result.context?.accountName)
        assertEquals("农业银行（0230）", PaymentAccountRules.canonicalName("农业银行(0230)"))
    }
}
