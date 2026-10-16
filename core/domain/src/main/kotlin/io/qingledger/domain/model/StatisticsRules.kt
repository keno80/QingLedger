package io.qingledger.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CategoryStatistic(val categoryId: String?, val amountMinor: Long, val share: Double)

object StatisticsRules {
    fun categories(rows: List<LedgerTransaction>): List<CategoryStatistic> {
        val amounts = rows.filter { it.kind == TransactionKind.EXPENSE || it.kind == TransactionKind.REFUND }
            .groupBy { it.categoryId }.mapValues { (_, items) -> LedgerRules.summarize(items).expense }
        val positiveTotal = amounts.values.filter { it > 0 }.fold(0L, Math::addExact)
        return amounts.entries.sortedWith(compareByDescending<Map.Entry<String?, Long>> { it.value }.thenBy { it.key ?: "" })
            .map { CategoryStatistic(it.key, it.value, if (positiveTotal > 0 && it.value > 0) it.value.toDouble() / positiveTotal else 0.0) }
    }

    fun days(rows: List<LedgerTransaction>, zone: ZoneId): Map<LocalDate, List<LedgerTransaction>> =
        rows.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
}
