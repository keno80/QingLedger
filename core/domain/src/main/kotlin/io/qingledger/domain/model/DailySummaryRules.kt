package io.qingledger.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

object DailySummaryRules {
    /** End is exclusive; adding a calendar day respects the book zone's DST transitions. */
    fun period(now: Instant, zone: ZoneId): PeriodRange {
        val date = now.atZone(zone).toLocalDate()
        return PeriodRange(
            date.atStartOfDay(zone).toInstant().toEpochMilli(),
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )
    }

    fun content(summary: LedgerSummary, pendingCount: Int): String = buildString {
        append("支出 ¥").append(amount(summary.expense))
        append(" · 收入 ¥").append(amount(summary.income))
        if (pendingCount > 0) append(" · 待确认 ").append(pendingCount).append(" 笔")
    }

    private fun amount(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()
}
