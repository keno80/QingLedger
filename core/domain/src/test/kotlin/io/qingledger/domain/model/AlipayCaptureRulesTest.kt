package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class AlipayCaptureRulesTest {
    @Test fun explicitlySelectedAlipayWalletIsCanonicalButListIsNotGuessed() {
        val cache = PaymentSessionCache()
        cache.observe("alipay", 1, "确认付款\n¥19.01\n余额宝\n测试银行储蓄卡(1234)", listOf("余额宝"), 100)
        assertEquals("余额宝", cache.consume("alipay", 1, NotificationProposal(1901, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE"), 101))
        cache.observe("alipay", 1, "确认付款\n¥19.01\n余额宝\n测试银行储蓄卡(1234)", emptyList(), 200)
        assertNull(cache.consume("alipay", 1, NotificationProposal(1901, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE"), 201))
    }
    @Test fun alipayOnlyPostsKnownSettledTemplatesNotMonthlySummaries() {
        assertNotNull(AlipayNoticeRules.propose("交易提醒\n你有一笔19.01元的支出，点此查看详情。"))
        assertEquals(TransactionKind.REFUND, AlipayNoticeRules.propose("退款提醒\n你收到一笔19.01元退款，点此查看账单详情！")!!.kind)
        listOf("本月支出19.01元", "支付19.01元得红包", "退款申请19.01元", "退款处理中19.01元", "你有一笔19.01元的支出待支付").forEach {
            assertNull(it, AlipayNoticeRules.propose(it))
        }
    }
    @Test fun realExpenseNotificationHasDirectionAndAmount() {
        val result = NotificationRules.propose("交易提醒\n你有一笔19.01元的支出，点此查看详情。")!!
        assertEquals(1901L, result.amountMinor)
        assertEquals(TransactionKind.EXPENSE, result.kind)
        assertEquals("UNVERIFIED_TEMPLATE", result.reason)
    }
    @Test fun receivedRefundReducesExpenseInsteadOfBecomingIncome() {
        val result = NotificationRules.propose("退款提醒\n你收到一笔19.01元退款，点此查看账单详情！")!!
        assertEquals(1901L, result.amountMinor)
        assertEquals(TransactionKind.REFUND, result.kind)
        assertEquals("UNVERIFIED_TEMPLATE", result.reason)
    }
    @Test fun requestFailureAndPromotionalRefundAreNeverPosted() {
        listOf("退款申请已提交 19.01元", "退款处理中 19.01元", "退款失败 19.01元", "你有一笔退款待领取 19.01元", "极速退款保障 19.01元", "建议5分钟内点击确认退款 19.01元").forEach { text ->
            assertTrue(text, NotificationRules.propose(text)?.kind == null)
        }
    }
    @Test fun advertisedSuccessOnConfirmationSheetIsNotPaymentSuccess() {
        assertNull(AccessibilityRules.propose("¥19.01\n支付成功得绿色能量5g\n余额宝\n确认付款"))
    }
    @Test fun confirmedRefundPageHasRefundKind() {
        assertEquals(TransactionKind.REFUND, AccessibilityRules.propose("退款成功\n19.01元")!!.kind)
        assertNull(AccessibilityRules.propose("退款申请\n19.01元"))
    }
}
