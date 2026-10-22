package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class OcrPaymentContextCacheTest {
    private val context = OcrPaymentContext(2800, "测试商户", "微信零钱")
    private val proposal = NotificationProposal(2800, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE")
    private fun page(value: OcrPaymentContext = context) = OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", value)
    @Test fun samePaymentConsumesOnceAndSelectionCanChange() {
        val cache = OcrPaymentContextCache()
        cache.observe("wechat", 1, page(), 0)
        cache.observe("wechat", 1, page(context.copy(accountName = "测试银行储蓄卡（1234）")), 500)
        assertEquals("测试银行储蓄卡（1234）", cache.consume("wechat", 1, proposal, "测试商户", 1000)?.accountName)
        assertNull(cache.consume("wechat", 1, proposal, "测试商户", 1001))
    }
    @Test fun pickerDoesNotOverwriteOrExtendExpiry() {
        val cache = OcrPaymentContextCache()
        cache.observe("wechat", 1, page(), 0)
        cache.observe("wechat", 1, OcrPaymentPageRules.Result("PAYMENT_METHOD_LIST"), 1000)
        assertEquals(context, cache.consume("wechat", 1, proposal, null, 2000))
        cache.observe("wechat", 1, page(), 0)
        cache.observe("wechat", 1, OcrPaymentPageRules.Result("PAYMENT_METHOD_LIST"), 119999)
        assertNull(cache.consume("wechat", 1, proposal, null, 120001))
    }
    @Test fun sourceGenerationAmountMerchantAndDirectionMustMatch() {
        listOf("other", "wechat").forEach { source ->
            val cache = OcrPaymentContextCache(); cache.observe("wechat", 1, page(), 0)
            assertNull(cache.consume(source, if (source == "wechat") 2 else 1, proposal, null, 1000))
        }
        listOf(proposal.copy(amountMinor = 2900), proposal.copy(kind = TransactionKind.INCOME)).forEach {
            val cache = OcrPaymentContextCache(); cache.observe("wechat", 1, page(), 0)
            assertNull(cache.consume("wechat", 1, it, null, 1000))
        }
        val cache = OcrPaymentContextCache(); cache.observe("wechat", 1, page(), 0)
        assertNull(cache.consume("wechat", 1, proposal, "另一商户", 1000))
    }
    @Test fun cancelFailureUnknownPageAndAmbiguousUpdatedAccountCannotReuseOldAccount() {
        listOf("PAYMENT_CONTEXT_CLEARED", "NO_SUCCESS_STATE", "PAYMENT_IN_PROGRESS").forEach {
            val cache = OcrPaymentContextCache(); cache.observe("wechat", 1, page(), 0)
            cache.observe("wechat", 1, OcrPaymentPageRules.Result(it), 500)
            assertNull(cache.consume("wechat", 1, proposal, null, 1000))
        }
        val cache = OcrPaymentContextCache(); cache.observe("wechat", 1, page(), 0)
        cache.observe("wechat", 1, page(context.copy(accountName = null)), 500)
        assertNull(cache.consume("wechat", 1, proposal, null, 1000)?.accountName)
    }
}
