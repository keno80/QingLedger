package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class PaymentContextReliabilityTest {
    private val page = OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", OcrPaymentContext(450, "商户", "测试银行储蓄卡（1234）"))
    private val paid = NotificationProposal(450, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE")
    @Test fun shortLivedContextSurvivesServiceRestartButNotExpiryOrConfigurationChange() {
        val first = OcrPaymentContextCache(); first.observe("wechat", 1, page, 1000)
        val restored = OcrPaymentContextCache(); restored.restore(first.snapshot(), 2000)
        assertEquals(page.context, restored.consume("wechat", 1, paid, "商户", 3000))
        restored.restore(first.snapshot(), 121001)
        assertNull(restored.consume("wechat", 1, paid, "商户", 122000))
        restored.restore(first.snapshot(), 2000)
        assertNull(restored.consume("wechat", 2, paid, "商户", 3000))
    }
    @Test fun missingExpiredAndConflictingContextHaveDistinctMetadataReasons() {
        val cache = OcrPaymentContextCache()
        assertEquals("PAYMENT_CONTEXT_MISSING", cache.consumeDetailed("wechat", 1, paid, null, 0).reason)
        cache.observe("wechat", 1, page, 0)
        assertEquals("PAYMENT_CONTEXT_EXPIRED", cache.consumeDetailed("wechat", 1, paid, null, 120001).reason)
        cache.observe("wechat", 1, page, 0)
        assertEquals("PAYMENT_CONTEXT_CONFLICT", cache.consumeDetailed("wechat", 1, paid.copy(amountMinor = 451), null, 1000).reason)
    }
    @Test fun authenticationKeepsAccountAndCancelButtonIsNotACancelledTransaction() {
        val cache = PaymentSessionCache()
        cache.observe("wechat", 1, "确认付款\n¥4.50\n付款方式\n微信零钱\n确认支付", emptyList(), 0)
        cache.observe("wechat", 1, "请输入支付密码\n取消支付", emptyList(), 500)
        assertEquals("微信零钱", cache.consume("wechat", 1, paid, 1000))
        assertFalse(PaymentCapturePhaseRules.isFailure("请输入支付密码\n取消支付"))
        assertTrue(PaymentCapturePhaseRules.isFailure("已取消"))
        assertNotNull(PaymentCapturePhaseRules.nodeConfirmation("确认付款\n¥4.50\n付款方式\n微信零钱\n确认支付"))
        assertNull(PaymentCapturePhaseRules.nodeConfirmation("选择付款方式\n微信零钱"))
    }
}
