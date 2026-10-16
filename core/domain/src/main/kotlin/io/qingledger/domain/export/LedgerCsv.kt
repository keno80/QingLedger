package io.qingledger.domain.export

import io.qingledger.domain.model.LedgerRules
import io.qingledger.domain.model.PeriodRange
import io.qingledger.domain.model.TransactionKind
import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class CsvExportScope { MONTH, ALL, CUSTOM }

data class CsvExportRequest(
    val scope: CsvExportScope,
    val startDate: String = "",
    val endDate: String = "",
    val requestedAt: Long = System.currentTimeMillis(),
) {
    fun period(zone: ZoneId, now: Instant = Instant.ofEpochMilli(requestedAt)): PeriodRange? = when (scope) {
        CsvExportScope.ALL -> null
        CsvExportScope.MONTH -> LedgerRules.period(YearMonth.from(now.atZone(zone)), zone)
        CsvExportScope.CUSTOM -> {
            val start = parseDate(startDate)
            val end = parseDate(endDate)
            require(!end.isBefore(start)) { "结束日期不能早于开始日期。" }
            PeriodRange(start.atStartOfDay(zone).toInstant().toEpochMilli(), end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }
    private fun parseDate(value: String): LocalDate = try {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value))
        LocalDate.parse(value).also { require(it.year in 1..9999) }
    } catch (_: Exception) { throw IllegalArgumentException("请输入有效日期，格式为 yyyy-MM-dd。") }
}

data class LedgerCsvRow(
    val id: String, val kind: TransactionKind, val amountMinor: Long, val occurredAt: Long,
    val zoneId: String, val currency: String, val category: String, val account: String,
    val fromAccount: String, val toAccount: String, val merchant: String, val note: String, val sourcePackage: String,
)

interface CsvExportRepository {
    suspend fun snapshot(request: CsvExportRequest): List<LedgerCsvRow>
    companion object { const val MAX_ROWS = 50_000 }
}

object LedgerCsv {
    val baseHeaders = listOf("账单ID", "交易时间", "时区", "收支类型", "金额（元）", "币种", "分类", "资金账户", "转出账户", "转入账户", "商户", "备注", "来源应用")
    val headers = baseHeaders + "文本转义"

    fun write(writer: Writer, rows: Iterable<LedgerCsvRow>) {
        writer.write("\uFEFF")
        writeRow(writer, headers)
        rows.forEach { row ->
            val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.of(row.zoneId)))
            val values = listOf(row.id, time, row.zoneId, row.kind.label, BigDecimal.valueOf(row.amountMinor, 2).toPlainString(),
                row.currency, row.category, row.account, row.fromAccount, row.toAccount, row.merchant, row.note, row.sourcePackage)
            val protected = values.mapIndexedNotNull { index, value -> index.takeIf { needsProtection(value) } }.joinToString(";")
            writeRow(writer, values + "v2:$protected")
        }
    }

    private fun writeRow(writer: Writer, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) writer.write(",")
            val guarded = if (needsProtection(value)) "'$value" else value
            writer.write("\"")
            writer.write(guarded.replace("\"", "\"\""))
            writer.write("\"")
        }
        writer.write("\r\n")
    }
    fun needsProtection(value: String): Boolean = value.firstOrNull() in listOf('\t', '\r', '\n') || value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')
}
