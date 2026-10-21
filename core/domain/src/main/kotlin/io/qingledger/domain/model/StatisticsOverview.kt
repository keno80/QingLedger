package io.qingledger.domain.model

import java.time.*
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

enum class StatisticsPeriod(val label: String) { DAY("日"), WEEK("周"), YEAR("年"), ALL("全部") }
data class StatisticsSelection(val period: StatisticsPeriod = StatisticsPeriod.DAY, val anchor: LocalDate = LocalDate.now()) {
    fun range(zone: ZoneId): PeriodRange? {
        val start = when (period) {
            StatisticsPeriod.DAY -> anchor
            StatisticsPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatisticsPeriod.YEAR -> anchor.withDayOfYear(1)
            StatisticsPeriod.ALL -> return null
        }
        val end = when (period) { StatisticsPeriod.DAY -> start.plusDays(1); StatisticsPeriod.WEEK -> start.plusWeeks(1); else -> start.plusYears(1) }
        return PeriodRange(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.atStartOfDay(zone).toInstant().toEpochMilli())
    }
    fun move(offset: Long): StatisticsSelection {
        val next = when (period) { StatisticsPeriod.DAY -> anchor.plusDays(offset); StatisticsPeriod.WEEK -> anchor.plusWeeks(offset); StatisticsPeriod.YEAR -> anchor.plusYears(offset); StatisticsPeriod.ALL -> anchor }
        return if (next.year in 2..9998) copy(anchor = next) else this
    }
}
data class StatisticsBucket(val label: String, val income: Long = 0, val expense: Long = 0)
data class StatisticsRanking(val categoryId: String?, val kind: TransactionKind, val amountMinor: Long, val share: Double)
data class StatisticsOverview(val summary: LedgerSummary = LedgerSummary(), val ranking: List<StatisticsRanking> = emptyList(), val trend: List<StatisticsBucket> = emptyList()) {
    companion object {
        fun calculate(rows: List<LedgerTransaction>, selection: StatisticsSelection, zone: ZoneId): StatisticsOverview {
            val range = selection.range(zone)
            val financial = rows.filter { it.kind != TransactionKind.TRANSFER && (range == null || it.occurredAt >= range.start && it.occurredAt < range.end) }
            val income = financial.filter { it.kind == TransactionKind.INCOME }.groupBy { it.categoryId }.mapValues { (_, values) -> LedgerRules.summarize(values).income }
            val expense = StatisticsRules.categories(financial)
            val incomeTotal = income.values.fold(0L, Math::addExact)
            val ranking = (income.map { (id, value) -> StatisticsRanking(id, TransactionKind.INCOME, value, if (incomeTotal > 0) value.toDouble() / incomeTotal else 0.0) } +
                expense.map { StatisticsRanking(it.categoryId, TransactionKind.EXPENSE, it.amountMinor, it.share) })
                .sortedWith(compareByDescending<StatisticsRanking> { abs(it.amountMinor) }.thenBy { it.kind.name }.thenBy { it.categoryId ?: "" })
            val dates = financial.map { Instant.ofEpochMilli(it.occurredAt).atZone(zone) }
            val firstYear = dates.minOfOrNull { it.year } ?: selection.anchor.year
            val lastYear = dates.maxOfOrNull { it.year } ?: firstYear
            val labels = when (selection.period) {
                StatisticsPeriod.DAY -> (0..5).map { "${(it * 4).toString().padStart(2, '0')}时" }
                StatisticsPeriod.WEEK -> listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
                StatisticsPeriod.YEAR -> (1..12).map { "${it}月" }
                StatisticsPeriod.ALL -> (firstYear..lastYear).map { it.toString() }
            }
            val buckets = Array(labels.size) { LedgerSummary() }
            financial.forEachIndexed { index, row ->
                val date = dates[index]
                val key = when (selection.period) { StatisticsPeriod.DAY -> date.hour / 4; StatisticsPeriod.WEEK -> date.dayOfWeek.value - 1; StatisticsPeriod.YEAR -> date.monthValue - 1; StatisticsPeriod.ALL -> date.year - firstYear }
                val old = buckets[key]
                buckets[key] = when (row.kind) {
                    TransactionKind.INCOME -> old.copy(income = Math.addExact(old.income, row.amountMinor))
                    TransactionKind.EXPENSE -> old.copy(expense = Math.addExact(old.expense, row.amountMinor))
                    TransactionKind.REFUND -> old.copy(expense = Math.subtractExact(old.expense, row.amountMinor))
                    TransactionKind.TRANSFER -> old
                }
            }
            return StatisticsOverview(LedgerRules.summarize(financial), ranking, labels.mapIndexed { i, label -> StatisticsBucket(label, buckets[i].income, buckets[i].expense) })
        }
    }
}
