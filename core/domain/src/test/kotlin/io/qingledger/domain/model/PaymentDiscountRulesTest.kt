package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class PaymentDiscountRulesTest {
    private fun line(text: String, y: Int, x: Int = 50, right: Int = 220) = OcrReceiptLine(text, x, y, right, y + 40)
    @Test fun explicitOriginalAndAppliedDiscountAreStoredWithoutChangingPaidAmount() {
        val value = PaymentDiscountRules.fromOcr(listOf(line("原价", 600), line("¥8.00", 600, 780, 950), line("优惠", 680), line("银行卡立减优惠0.40元", 680, 350, 950)), 760, 550, 800)
        assertEquals(PaymentDiscount(800, 40), value)
    }
    @Test fun unrecognizedFieldsStayNullAndZeroDiscountIsValid() {
        assertEquals(PaymentDiscount(), PaymentDiscountRules.fromOcr(listOf(line("请选择优惠券", 600)), 760, 550, 800))
        assertEquals(PaymentDiscount(760, 0), PaymentDiscountRules.fromOcr(listOf(line("原价¥7.60", 600), line("优惠0.00元", 680)), 760, 550, 800))
    }
    @Test fun conditionalAndCashbackMarketingNeverBecomesAppliedDiscount() {
        listOf("优惠最高0.40元", "使用其他卡优惠0.40元", "返现0.40元", "领取优惠0.40元", "优惠满8减0.40元", "优惠3期免息", "优惠3折", "优惠3元起", "优惠券余额0.40元").forEach { text ->
            assertNull(PaymentDiscountRules.fromOcr(listOf(line("优惠", 600), line(text, 600, 350, 950)), 760, 550, 800).discountMinor)
        }
    }
    @Test fun conflictingValuesDoNotPoisonTheMainPayment() {
        assertEquals(PaymentDiscount(), PaymentDiscountRules.fromOcr(listOf(line("原价¥8.00", 600), line("优惠0.50元", 680)), 760, 550, 800))
        assertNull(PaymentDiscountRules.fromOcr(listOf(line("优惠0.40元", 600), line("优惠0.30元", 680)), 760, 550, 800).discountMinor)
    }
    @Test fun rulesRejectImpossibleOrNonExpenseMetadataAndAcceptPartialInformation() {
        assertNull(PaymentDiscountRules.validate(TransactionKind.EXPENSE, 760, 800, 40))
        assertNull(PaymentDiscountRules.validate(TransactionKind.EXPENSE, 760, null, 40))
        assertNull(PaymentDiscountRules.validate(TransactionKind.EXPENSE, 760, 800, null))
        assertNotNull(PaymentDiscountRules.validate(TransactionKind.EXPENSE, 760, 800, 50))
        assertNotNull(PaymentDiscountRules.validate(TransactionKind.EXPENSE, 760, 700, null))
        assertNotNull(PaymentDiscountRules.validate(TransactionKind.EXPENSE, 760, null, -1))
        assertNotNull(PaymentDiscountRules.validate(TransactionKind.INCOME, 760, 800, 40))
    }
    @Test fun retryOnlyComplementsMatchingPricesAndConflictsClearThem() {
        assertEquals(PaymentDiscount(800, 40), PaymentDiscountRules.merge(760, PaymentDiscount(null, 40), PaymentDiscount(800, null)))
        assertEquals(PaymentDiscount(), PaymentDiscountRules.merge(760, PaymentDiscount(null, 40), PaymentDiscount(null, 50)))
        val page = OcrPaymentPageRules.evaluate(listOf(line("使用密码", 200), line("测试商户", 400, 350, 650),
            OcrReceiptLine("¥7.60", 300, 500, 700, 600), line("原价¥8.00", 650), line("优惠0.40元", 720), line("付款方式", 800), line("微信零钱", 950), line("支付", 1700)), 1000, 2000)
        assertEquals(800L, page.context?.originalAmountMinor)
        assertEquals(40L, page.context?.discountMinor)
        val cache = OcrPaymentContextCache()
        cache.observe("test", 1, page, 0)
        val context = cache.consume("test", 1, NotificationProposal(760, TransactionKind.EXPENSE, "test"), "测试商户", 1000)
        assertEquals(page.context, context)
        assertNull(cache.consume("test", 1, NotificationProposal(760, TransactionKind.EXPENSE, "test"), "测试商户", 1001))
    }
}
