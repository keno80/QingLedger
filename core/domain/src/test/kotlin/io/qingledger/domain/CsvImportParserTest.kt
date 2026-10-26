package io.qingledger.domain

import io.qingledger.domain.export.*
import io.qingledger.domain.importing.*
import io.qingledger.domain.model.TransactionKind
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class CsvImportParserTest {
    private fun row(id: String = "tx-1") = LedgerCsvRow(id, TransactionKind.EXPENSE, 1234, 0,
        "Asia/Shanghai", "CNY", "餐饮", "现金", "", "", "商户", "备注", "com.tencent.mm")
    private fun csv(vararg rows: LedgerCsvRow) = StringWriter().also { LedgerCsv.write(it, rows.asIterable()) }.toString()

    @Test fun roundTripKeepsQuotedMultilineTextAndFormulaProtectionUnambiguous() {
        val original = row().copy(merchant = "=店,铺\"名称\"", note = "第一行\r\n第二行\"备注\"")
        val result = CsvImportParser.parse(csv(original, row("tx-2").copy(note = "'=原本的单引号")))
        assertTrue(result.issues.isEmpty())
        assertEquals(original.merchant, result.records.first().merchant)
        assertEquals(original.note, result.records.first().note)
        assertEquals("'=原本的单引号", result.records.last().note)
        assertEquals(0L, result.records.first().occurredAt)
        assertFalse(result.legacy)
    }
    @Test fun legacyThirteenColumnsAreAcceptedWithoutGuessingApostrophes() {
        val original = csv(row().copy(note = "=SUM(A1)"))
        val legacy = original.lineSequence().take(2).joinToString("\r\n") { line -> (1..4).fold(line) { value, _ -> value.substringBeforeLast(',') } } + "\r\n"
        val parsed = CsvImportParser.parse(legacy)
        assertTrue(parsed.legacy)
        assertEquals("'=SUM(A1)", parsed.records.single().note)
    }
    @Test fun invalidRowsDoNotHideValidRows() {
        val input = csv(row(), row("bad").copy(amountMinor = 0))
        val parsed = CsvImportParser.parse(input)
        assertEquals(2, parsed.totalRows)
        assertEquals(1, parsed.records.size)
        assertEquals(1, parsed.issues.size)
        assertEquals(3, parsed.issues.single().line)
    }
    @Test fun brokenQuotesAndUnsupportedHeaderAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvImportParser.parse("\"broken") }
        assertThrows(IllegalArgumentException::class.java) { CsvImportParser.parse("金额,备注\r\n1,测试") }
    }
    @Test fun timezoneMismatchAndInvalidTransferAreReported() {
        val wrongZone = csv(row()).replace("1970-01-01T08:00:00+08:00", "1970-01-01T08:00:00+00:00")
        assertEquals(1, CsvImportParser.parse(wrongZone).issues.size)
        val transfer = row().copy(kind = TransactionKind.TRANSFER, category = "", account = "", fromAccount = "", toAccount = "现金")
        assertEquals(1, CsvImportParser.parse(csv(transfer)).issues.size)
    }
    @Test fun blankLinesAndHeaderOnlyHaveNoRecords() {
        assertEquals(0, CsvImportParser.parse(csv()).totalRows)
        assertEquals(1, CsvImportParser.parse(csv(row()) + "\r\n\r\n").totalRows)
    }
    @Test fun malformedProtectionMetadataIsReportedPerRow() {
        val parsed = CsvImportParser.parse(csv(row()).replace("\"v4:\"", "\"v4:999999999999999999999\""))
        assertEquals("文本转义字段无效。", parsed.issues.single().message)
        assertTrue(parsed.records.isEmpty())
    }
    @Test fun oversizedFilesAndTooManyRecordsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CsvImportParser.parse("x".repeat(CsvImportRepository.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { CsvImportParser.parse(csv() + "x\r\n".repeat(CsvImportRepository.MAX_ROWS + 1)) }
    }
    @Test fun refundLinkRoundTripsAndV2RemainsReadable() {
        val value = row("refund").copy(kind = TransactionKind.REFUND, originalExpenseId = "expense-1")
        assertEquals("expense-1", CsvImportParser.parse(csv(value)).records.single().originalExpenseId)
        val v2 = csv(row()).replace("\"原支出ID\",\"原价（元）\",\"优惠（元）\",", "").replace(",\"\",\"\",\"\",\"v4:", ",\"v2:")
        assertEquals("tx-1", CsvImportParser.parse(v2).records.single().id)
    }
}
