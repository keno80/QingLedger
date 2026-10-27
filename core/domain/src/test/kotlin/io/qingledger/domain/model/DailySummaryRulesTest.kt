package io.qingledger.domain.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DailySummaryRulesTest {
    @Test fun todayUsesBookZoneAndAnExclusiveNextDayBoundary() {
        val today = DailySummaryRules.period(Instant.parse("2026-10-26T16:30:00Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals(Instant.parse("2026-10-26T16:00:00Z").toEpochMilli(), today.start)
        assertEquals(Instant.parse("2026-10-27T16:00:00Z").toEpochMilli(), today.end)
    }

    @Test fun crossingMidnightSelectsTheNewBookDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val before = DailySummaryRules.period(Instant.parse("2026-10-27T15:59:59.999Z"), zone)
        val after = DailySummaryRules.period(Instant.parse("2026-10-27T16:00:00Z"), zone)
        assertEquals(before.end, after.start)
        assertNotEquals(before, after)
    }

    @Test fun springClockChangeHas23HoursAndFallClockChangeHas25Hours() {
        val zone = ZoneId.of("America/New_York")
        val spring = DailySummaryRules.period(Instant.parse("2026-03-08T12:00:00Z"), zone)
        val fall = DailySummaryRules.period(Instant.parse("2026-11-01T12:00:00Z"), zone)
        assertEquals(23 * 60 * 60 * 1000L, spring.end - spring.start)
        assertEquals(25 * 60 * 60 * 1000L, fall.end - fall.start)
    }

    @Test fun contentUsesTheCompleteSummaryWithoutClampingRefunds() {
        assertEquals("支出 ¥-12.34 · 收入 ¥900.01 · 待确认 2 笔", DailySummaryRules.content(LedgerSummary(income = 90_001, expense = -1_234), 2))
        assertEquals("支出 ¥0.00 · 收入 ¥0.00", DailySummaryRules.content(LedgerSummary(), 0))
    }
}
