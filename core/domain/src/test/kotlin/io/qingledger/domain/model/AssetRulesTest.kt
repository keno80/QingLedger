package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class AssetRulesTest {
    @Test fun openingBalanceAllowsZeroDebtAndExactCents() {
        assertEquals(0L, AssetRules.parseOpeningBalance("0"))
        assertEquals(-1234L, AssetRules.parseOpeningBalance(" -12.34 "))
        assertEquals(10L, AssetRules.parseOpeningBalance("0.1"))
        assertEquals(-LedgerRules.MAX_AMOUNT, AssetRules.parseOpeningBalance("-9999999999.99"))
        listOf("", "-", "1.001", "1e3", "1,000", "10000000000", "-10000000000").forEach { assertNull(it, AssetRules.parseOpeningBalance(it)) }
    }
    @Test fun unknownOpeningBalanceDoesNotBecomeMovementBalance() {
        val row = AccountBalance(Account("a", "零钱"), 10000)
        assertNull(row.balanceMinor)
        assertEquals(AssetSummary(accountCount = 1), AssetRules.summarize(listOf(row)))
    }
    @Test fun summaryKeepsDebtAndPartialCoverageExplicit() {
        val rows = listOf(AccountBalance(Account("a", "现金", openingBalanceMinor = 10000), -500), AccountBalance(Account("b", "信用卡", openingBalanceMinor = -3000), 500), AccountBalance(Account("c", "未知"), 999))
        val sum = AssetRules.summarize(rows)
        assertEquals(9500L, sum.assetsMinor)
        assertEquals(2500L, sum.liabilitiesMinor)
        assertEquals(7000L, sum.netMinor)
        assertEquals(2, sum.configuredCount)
        assertEquals(3, sum.accountCount)
    }
    @Test fun archivedAccountStillContributesToHoldings() {
        assertEquals(-100L, AssetRules.summarize(listOf(AccountBalance(Account("a", "旧卡", true, -100), 0))).netMinor)
    }
    @Test(expected = ArithmeticException::class) fun overflowCannotWrapIntoFalseBalance() {
        AccountBalance(Account("a", "现金", openingBalanceMinor = 100), Long.MAX_VALUE).balanceMinor
    }
}
