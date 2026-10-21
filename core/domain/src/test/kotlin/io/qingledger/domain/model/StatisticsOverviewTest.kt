package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class StatisticsOverviewTest {
    private val zone = ZoneId.of("America/New_York")
    private fun row(kind: TransactionKind, amount: Long, time: String, category: String? = "food") =
        LedgerTransaction("$kind-$time", kind, amount, Instant.parse(time).toEpochMilli(), "", category, null, null, null, "", 0, "UTC")

    @Test fun periodsFollowBookZoneNaturalWeekAndDst() {
        val date = LocalDate.of(2026, 3, 8)
        val day = StatisticsSelection(StatisticsPeriod.DAY, date).range(zone)!!
        assertEquals(23L, Duration.ofMillis(day.end - day.start).toHours())
        val week = StatisticsSelection(StatisticsPeriod.WEEK, LocalDate.of(2026, 1, 1)).range(zone)!!
        assertEquals(LocalDate.of(2025, 12, 29).atStartOfDay(zone).toInstant().toEpochMilli(), week.start)
        assertEquals(LocalDate.of(2026, 1, 5).atStartOfDay(zone).toInstant().toEpochMilli(), week.end)
        assertNull(StatisticsSelection(StatisticsPeriod.ALL, date).range(zone))
    }
    @Test fun dailyBucketsKeepRefundsAndLocalCrossDayWithoutLosingTotals() {
        val date = LocalDate.of(2026, 10, 14)
        val rows = listOf(row(TransactionKind.INCOME, 20000, "2026-10-14T12:00:00Z"), row(TransactionKind.EXPENSE, 5200, "2026-10-14T16:00:00Z"), row(TransactionKind.REFUND, 8000, "2026-10-14T18:00:00Z"))
        val result = StatisticsOverview.calculate(rows, StatisticsSelection(StatisticsPeriod.DAY, date), zone)
        assertEquals(6, result.trend.size)
        assertEquals(20000L, result.summary.income)
        assertEquals(-2800L, result.summary.expense)
        assertEquals(result.summary.income, result.trend.sumOf { it.income })
        assertEquals(result.summary.expense, result.trend.sumOf { it.expense })
        assertEquals(-2800L, result.ranking.first { it.kind == TransactionKind.EXPENSE }.amountMinor)
        assertEquals(0.0, result.ranking.first { it.kind == TransactionKind.EXPENSE }.share, 0.0)
    }
    @Test fun mixedRankingUsesSeparateDenominatorsAndExcludesTransfersAndOutsidePeriod() {
        val rows = listOf(row(TransactionKind.INCOME, 9000, "2026-10-14T12:00:00Z", "salary"), row(TransactionKind.EXPENSE, 1000, "2026-10-14T12:00:00Z"), row(TransactionKind.TRANSFER, 99999, "2026-10-14T12:00:00Z"), row(TransactionKind.EXPENSE, 500, "2025-12-31T12:00:00Z"))
        val result = StatisticsOverview.calculate(rows, StatisticsSelection(StatisticsPeriod.YEAR, LocalDate.of(2026, 10, 14)), zone)
        assertEquals(12, result.trend.size)
        assertEquals(2, result.ranking.size)
        assertEquals(listOf(9000L, 1000L), result.ranking.map { it.amountMinor })
        assertTrue(result.ranking.all { it.share == 1.0 })
        val all = StatisticsOverview.calculate(rows, StatisticsSelection(StatisticsPeriod.ALL, LocalDate.of(2026, 10, 14)), zone)
        assertEquals(listOf("2025", "2026"), all.trend.map { it.label })
        assertEquals(1500L, all.summary.expense)
    }
    @Test fun emptyPeriodsHaveStableBucketsAndLeapYearMovesSafely() {
        val selection = StatisticsSelection(StatisticsPeriod.YEAR, LocalDate.of(2024, 2, 29))
        assertEquals(LocalDate.of(2025, 2, 28), selection.move(1).anchor)
        assertEquals(7, StatisticsOverview.calculate(emptyList(), selection.copy(period = StatisticsPeriod.WEEK), zone).trend.size)
        assertTrue(StatisticsOverview.calculate(emptyList(), selection, zone).ranking.isEmpty())
    }
    @Test fun dayUsesLocalDatesAndBoundsAreHalfOpen() {
        val date = LocalDate.of(2026, 10, 14)
        val selection = StatisticsSelection(StatisticsPeriod.DAY, date)
        val rows = listOf(row(TransactionKind.EXPENSE, 100, "2026-10-14T04:00:00Z"), row(TransactionKind.EXPENSE, 200, "2026-10-15T03:59:59Z"), row(TransactionKind.EXPENSE, 999, "2026-10-15T04:00:00Z"), row(TransactionKind.EXPENSE, 999, "2026-10-14T03:59:59Z"))
        val result = StatisticsOverview.calculate(rows, selection, zone)
        assertEquals(300L, result.summary.expense)
        assertEquals(100L, result.trend.first().expense)
        assertEquals(200L, result.trend.last().expense)
    }
}
