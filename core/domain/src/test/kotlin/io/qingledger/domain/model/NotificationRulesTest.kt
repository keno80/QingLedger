package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class NotificationRulesTest {
    @Test fun unknownPaymentOnlyProducesReviewProposal() {
        val proposal = NotificationRules.propose("付款成功，支付金额 ¥12.34")!!
        assertEquals(1234L, proposal.amountMinor)
        assertEquals(TransactionKind.EXPENSE, proposal.kind)
        assertEquals("UNVERIFIED_TEMPLATE", proposal.reason)
    }
    @Test fun nonTransactionAndUnsuccessfulMessagesAreDiscarded() {
        listOf("您的验证码 123456", "账户余额 100.00 元", "支付失败 ¥12.34", "领取优惠券 满减 10 元", "你好，今天去哪里").forEach {
            assertNull(it, NotificationRules.propose(it))
        }
    }
    @Test fun conflictingAmountsAreNotGuessed() {
        val proposal = NotificationRules.propose("支付成功 原价 20 元 实付 15 元")!!
        assertNull(proposal.amountMinor)
        assertEquals("AMBIGUOUS_AMOUNT", proposal.reason)
    }
    @Test fun repeatedAmountCanBeCollapsedWithoutMergingEvents() {
        assertEquals(1234L, NotificationRules.propose("支付成功 ¥12.34，付款金额 12.34 元")!!.amountMinor)
    }
    @Test fun refundsAndTransfersNeverBecomeIncomeByDefault() {
        assertNull(NotificationRules.propose("退款到账 10 元")!!.kind)
        assertEquals("REFUND_REVIEW", NotificationRules.propose("退款到账 10 元")!!.reason)
        assertNull(NotificationRules.propose("收到转账 10 元")!!.kind)
    }
    @Test fun missingAmountAndMixedDirectionRemainUncertain() {
        assertNull(NotificationRules.propose("支付成功")!!.amountMinor)
        assertNull(NotificationRules.propose("支付收款 ¥12.34")!!.kind)
    }
}
