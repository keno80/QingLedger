package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatisticsRulesTest {
    private fun row(kind: TransactionKind, amount: Long, category: String? = "food", time: String = "2026-10-15T16:30:00Z") =
        LedgerTransaction("$kind-$amount", kind, amount, Instant.parse(time).toEpochMilli(), "", category, null, null, null, "", 0, "UTC")

    @Test fun refundsReduceCategoriesAndTransfersAreExcluded() {
        val rows = listOf(row(TransactionKind.EXPENSE, 1000), row(TransactionKind.REFUND, 300), row(TransactionKind.INCOME, 9000), row(TransactionKind.TRANSFER, 500))
        assertEquals(700L, StatisticsRules.categories(rows).single().amountMinor)
        assertEquals(1.0, StatisticsRules.categories(rows).single().share, 0.0001)
    }
    @Test fun negativeRefundCategoriesRemainVisibleWithoutNegativeArcs() {
        val groups = StatisticsRules.categories(listOf(row(TransactionKind.EXPENSE, 1000), row(TransactionKind.REFUND, 200, "other")))
        assertEquals(listOf(1000L, -200L), groups.map { it.amountMinor })
        assertEquals(listOf(1.0, 0.0), groups.map { it.share })
    }
    @Test fun dayGroupingUsesBookTimezoneAndKeepsAllRows() {
        val rows = List(101) { row(TransactionKind.EXPENSE, 1) }
        val day = StatisticsRules.days(rows, ZoneId.of("Asia/Shanghai")).keys.single()
        assertEquals("2026-10-16", day.toString())
        assertEquals(101L, LedgerRules.summarize(StatisticsRules.days(rows, ZoneId.of("Asia/Shanghai")).getValue(day)).expense)
        assertTrue(StatisticsRules.categories(emptyList()).isEmpty())
    }
}
