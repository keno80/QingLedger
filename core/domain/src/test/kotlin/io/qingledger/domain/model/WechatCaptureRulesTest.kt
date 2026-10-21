package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class WechatCaptureRulesTest {
    @Test fun diagnosticReasonsDistinguishEmptyDraftMissingStateAndAmbiguousAmount() {
        assertEquals("PAGE_EMPTY", AccessibilityRules.evaluate("").reason)
        assertEquals("PAYMENT_IN_PROGRESS", AccessibilityRules.evaluate("确认付款\n¥19.01").reason)
        assertEquals("NO_SUCCESS_STATE", AccessibilityRules.evaluate("微信支付\n¥19.01").reason)
        assertEquals("MISSING_AMOUNT", AccessibilityRules.evaluate("支付成功\n完成").reason)
        assertEquals("AMBIGUOUS_AMOUNT", AccessibilityRules.evaluate("支付成功\n¥19.01\n余额99.00元").reason)
        assertEquals("RECOGNIZED", AccessibilityRules.evaluate("支付成功 19.01元").reason)
    }
    @Test fun successfulStatusAndAmountCanShareOneAccessibleNode() {
        listOf("支付成功 ¥19.01", "付款成功19.01元", "支付成功\u00a0￥19.01").forEach {
            val result = AccessibilityRules.propose(it)!!
            assertEquals(it, 1901L, result.amountMinor)
            assertEquals(it, TransactionKind.EXPENSE, result.kind)
        }
    }
    @Test fun standaloneDecimalNextToSuccessCanBeAmountWithoutCurrencySymbol() {
        val result = AccessibilityRules.propose("支付成功\n19.01\n完成")!!
        assertEquals(1901L, result.amountMinor)
        assertEquals(TransactionKind.EXPENSE, result.kind)
        assertEquals(TransactionKind.INCOME, AccessibilityRules.propose("收款成功 19.01")!!.kind)
    }
    @Test fun multipleBareAmountsAndDistantNumbersAreNotGuessed() {
        assertNull(AccessibilityRules.propose("支付成功\n19.01\n99.00"))
        assertNull(AccessibilityRules.propose("支付成功\n商户\n完成\n19.01"))
        assertNull(AccessibilityRules.propose("支付成功\n19"))
    }
    @Test fun mergedNodeDoesNotRelaxDraftHistoryOrAdvertisementChecks() {
        listOf("支付成功得绿色能量5g\n¥19.01", "确认付款\n支付成功 ¥19.01", "交易记录\n付款成功19.01元").forEach {
            assertNull(it, AccessibilityRules.propose(it))
        }
    }
    @Test fun conflictingSuccessDirectionsAreNotGuessed() {
        assertNull(AccessibilityRules.propose("支付成功\n退款成功\n¥19.01"))
    }
}
