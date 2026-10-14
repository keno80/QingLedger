package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class PaymentSessionCacheTest {
    private val success = NotificationProposal(1888, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE")
    private val page = "确认付款\n¥18.88\n付款方式\n微信零钱\n测试银行储蓄卡（1234）\n确认支付"
    @Test fun selectedAccountIsLinkedOnlyToMatchingSuccessAndConsumed() {
        val cache = PaymentSessionCache()
        cache.observe("test", 1, page, listOf("微信零钱"), 0)
        assertEquals("微信零钱", cache.consume("test", 1, success, 1000))
        assertNull(cache.consume("test", 1, success, 1100))
    }
    @Test fun changingSelectionReplacesPreviousChoice() {
        val cache = PaymentSessionCache()
        cache.observe("test", 1, page, listOf("微信零钱"), 0)
        cache.observe("test", 1, page, listOf("测试银行储蓄卡（1234）"), 500)
        assertEquals("测试银行储蓄卡（1234）", cache.consume("test", 1, success, 1000))
    }
    @Test fun failureCancelAndLeavingConfirmationClearCache() {
        listOf("付款失败\n¥18.88", "已取消", "测试首页").forEach {
            val cache = PaymentSessionCache()
            cache.observe("test", 1, page, listOf("微信零钱"), 0)
            cache.observe("test", 1, it, emptyList(), 500)
            assertNull(cache.consume("test", 1, success, 1000))
        }
    }
    @Test fun expirySourceAmountGenerationAndIncomeCannotReuseChoice() {
        val checks = listOf<Pair<NotificationProposal, Triple<String, Long, Long>>>(
            success to Triple("test", 1L, 120001L), success to Triple("other", 1L, 1000L),
            success.copy(amountMinor = 1999) to Triple("test", 1L, 1000L),
            success to Triple("test", 2L, 1000L), success.copy(kind = TransactionKind.INCOME) to Triple("test", 1L, 1000L),
        )
        checks.forEach { (proposal, args) ->
            val cache = PaymentSessionCache(); cache.observe("test", 1, page, listOf("微信零钱"), 0)
            assertNull(cache.consume(args.first, args.second, proposal, args.third))
        }
    }
    @Test fun unknownAndMultipleSelectionsClearPreviousAccount() {
        listOf(listOf("未提供付款方式"), listOf("微信零钱", "测试银行储蓄卡（1234）")).forEach {
            val cache = PaymentSessionCache(); cache.observe("test", 1, page, listOf("微信零钱"), 0)
            cache.observe("test", 1, page, it, 500)
            assertNull(cache.consume("test", 1, success, 1000))
        }
    }
    @Test fun balancesAreNotUsedAsTransactionAmount() {
        val cache = PaymentSessionCache()
        cache.observe("test", 1, page + "\n余额：99.99元", listOf("微信零钱"), 0)
        assertEquals("微信零钱", cache.consume("test", 1, success, 1000))
    }
}
