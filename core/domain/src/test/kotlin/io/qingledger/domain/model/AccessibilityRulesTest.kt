package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessibilityRulesTest {
    @Test fun walletSelectionOnlyBooksAfterSuccess() {
        assertNull(AccessibilityRules.propose("选择付款方式\n付款方式：零钱\n¥12.34\n支付"))
        val result = AccessibilityRules.propose("支付成功\n¥12.34\n付款方式：零钱")!!
        assertEquals("微信零钱", result.accountName)
        assertEquals(1234L, result.amountMinor)
    }
    @Test fun bankAccountsKeepSeparateLastFourDigits() {
        assertEquals("测试银行储蓄卡（1234）", AccessibilityRules.propose("支付成功\n¥12.34\n付款方式：测试银行储蓄卡(1234)")!!.accountName)
        assertEquals("测试银行储蓄卡（5678）", AccessibilityRules.propose("支付成功\n¥12.34\n付款方式：测试银行储蓄卡（5678）")!!.accountName)
    }
    @Test fun missingConflictingAndIncompleteMethodsStayUnspecified() {
        listOf("", "付款方式：银行卡", "付款方式：零钱\n付款方式：测试银行储蓄卡（1234）")
            .forEach { assertNull(AccessibilityRules.propose("支付成功\n¥12.34\n$it")!!.accountName) }
        assertNull(AccessibilityRules.propose("付款失败\n¥12.34\n付款方式：零钱"))
    }
    @Test fun incomeUsesDestinationAndIgnoresPayersPaymentMethod() {
        val result = AccessibilityRules.propose("收款成功\n¥12.34\n入账账户：微信零钱\n付款方式：测试银行储蓄卡（1234）")!!
        assertEquals(TransactionKind.INCOME, result.kind)
        assertEquals("微信零钱", result.accountName)
        assertNull(AccessibilityRules.propose("收款成功\n¥12.34\n付款方式：测试银行储蓄卡（1234）")!!.accountName)
    }
    @Test fun simulatorReceiptFixtureDoesNotConfuseIncomeWithBranding() {
        val text = "微信风格 · 测试页面\n仅模拟，无资金变动\n✓\n收款成功\n¥18.88\n商户：测试便利店\n场景：INCOME\n页面刷新次数：0\n重复刷新页面（同笔）\n返回测试首页"
        val proposal = AccessibilityRules.propose(text)!!
        assertEquals(1888L, proposal.amountMinor)
        assertEquals(TransactionKind.INCOME, proposal.kind)
    }
    @Test fun simulatorFailureAndMultipleAmountFixturesAreRejected() {
        assertNull(AccessibilityRules.propose("微信风格 · 测试页面\n付款失败\n¥18.88"))
        assertNull(AccessibilityRules.propose("微信风格 · 测试页面\n支付成功\n¥18.88\n余额：99.99元"))
    }
    @Test fun explicitPaymentSuccessProducesExpense() {
        val result = AccessibilityRules.propose("支付成功\n￥\n23.50\n完成")!!
        assertEquals(2350L, result.amountMinor)
        assertEquals(TransactionKind.EXPENSE, result.kind)
    }
    @Test fun explicitReceiptSuccessProducesIncome() {
        assertEquals(TransactionKind.INCOME, AccessibilityRules.propose("收款成功\n12.34元")!!.kind)
    }
    @Test fun historyChatAndPaymentDraftDoNotBecomeTransactions() {
        listOf("付款给张三\n23.50元", "张三：支付23.50元", "交易记录\n支付成功\n23.50元", "账单列表\n收款成功\n12.34元", "交易成功\n12.34元")
            .forEach { assertNull(AccessibilityRules.propose(it)) }
    }
    @Test fun uncertainAmountsAndRefundsAreNotPosted() {
        assertNull(AccessibilityRules.propose("支付成功\n实付23.50元\n余额99.00元"))
        assertNull(AccessibilityRules.propose("退款成功\n23.50元"))
        assertNull(AccessibilityRules.propose("支付成功\nUSD23.50"))
        assertNull(AccessibilityRules.propose("支付成功\n支付失败\n23.50元"))
    }
}
