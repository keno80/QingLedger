package io.qingledger.domain.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LedgerRulesTest {
    @Test fun decimalAmountRetainsExactCents() {
        assertEquals(1234L, LedgerRules.parseAmount("12.34"))
        assertEquals(10L, LedgerRules.parseAmount("0.1"))
        assertEquals(100L, LedgerRules.parseAmount(" 1.00 "))
    }
    @Test fun invalidAmountsCannotEnterLedger() {
        listOf("", "0", "-1", "1.001", "1e3", "NaN", "1,000", "9223372036854775808").forEach {
            assertNull(it, LedgerRules.parseAmount(it))
        }
    }
    @Test fun amountUpperBoundIsEnforced() {
        assertEquals(LedgerRules.MAX_AMOUNT, LedgerRules.parseAmount("9999999999.99"))
        assertNull(LedgerRules.parseAmount("10000000000"))
    }
    @Test fun monthUsesLocalTimezoneAndExclusiveEnd() {
        val period = LedgerRules.period(YearMonth.of(2026, 10), ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2026-09-30T16:00:00Z").toEpochMilli(), period.start)
        assertEquals(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli(), period.end)
    }
    @Test fun daylightSavingMonthDoesNotAssumeFixedDayLength() {
        val period = LedgerRules.period(YearMonth.of(2026, 3), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-01T05:00:00Z").toEpochMilli(), period.start)
        assertEquals(Instant.parse("2026-04-01T04:00:00Z").toEpochMilli(), period.end)
    }
    @Test fun transferRequiresTwoDistinctAccounts() {
        val draft = TransactionDraft(TransactionKind.TRANSFER, 100, 0, fromAccountId = "a", toAccountId = "a")
        assertNotNull(LedgerRules.validate(draft))
        assertNotNull(LedgerRules.validate(draft.copy(toAccountId = null)))
        assertNull(LedgerRules.validate(draft.copy(toAccountId = "b")))
    }
    @Test fun incomeAndExpenseCannotCarryTransferFields() {
        assertNotNull(LedgerRules.validate(TransactionDraft(TransactionKind.EXPENSE, 100, 0, fromAccountId = "a")))
        assertNull(LedgerRules.validate(TransactionDraft(TransactionKind.INCOME, 100, 0)))
    }
    @Test fun transferDoesNotInflateIncomeOrExpense() {
        fun row(kind: TransactionKind, amount: Long) = LedgerTransaction("test", kind, amount, 0, "", null, null, null, null, "", 0, "Asia/Shanghai")
        val summary = LedgerRules.summarize(listOf(row(TransactionKind.INCOME, 10000), row(TransactionKind.EXPENSE, 2500), row(TransactionKind.TRANSFER, 3000)))
        assertEquals(10000L, summary.income)
        assertEquals(2500L, summary.expense)
        assertEquals(7500L, summary.balance)
    }
}
