package io.qingledger.domain

import io.qingledger.domain.export.*
import io.qingledger.domain.model.TransactionKind
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LedgerCsvTest {
    private fun row(merchant: String = "商户", note: String = "") = LedgerCsvRow(
        id = "id-1", kind = TransactionKind.EXPENSE, amountMinor = 1, occurredAt = 0,
        zoneId = "Asia/Shanghai", currency = "CNY", category = "餐饮", account = "微信零钱",
        fromAccount = "", toAccount = "", merchant = merchant, note = note, sourcePackage = "com.tencent.mm",
    )
    private fun csv(vararg rows: LedgerCsvRow) = StringWriter().also { LedgerCsv.write(it, rows.asIterable()) }.toString()

    @Test fun quotesCommasNewlinesAndDoubleQuotesWithoutLosingText() {
        val text = csv(row("店,铺", "第一行\r\n第二行\"备注\""))
        assertTrue(text.startsWith("\uFEFF\"账单ID\""))
        assertTrue(text.contains("\"店,铺\",\"第一行\r\n第二行\"\"备注\"\"\""))
        assertTrue(text.endsWith("\r\n"))
    }
    @Test fun writesExactTwoDecimalMoneyAndOffsetTime() {
        val text = csv(row(), row().copy(amountMinor = 999_999_999_999L, kind = TransactionKind.TRANSFER, account = "", fromAccount = "银行卡", toAccount = "零钱"))
        assertTrue(text.contains("\"0.01\""))
        assertTrue(text.contains("\"9999999999.99\""))
        assertTrue(text.contains("\"1970-01-01T08:00:00+08:00\""))
        assertTrue(text.contains("\"转账\""))
    }
    @Test fun escapesSpreadsheetFormulaPrefixesIncludingLeadingWhitespace() {
        listOf("=1+1", "+SUM(A1)", "-1+1", "@SUM(A1)", " \t=1", "\t文字", "\r文字").forEach { value ->
            assertTrue(csv(row(merchant = value)).contains("\"'${value}\""))
        }
        assertTrue(csv(row(merchant = "普通商户")).contains("\"普通商户\""))
    }
    @Test fun emptyExportStillHasHeader() { assertEquals(1, csv().count { it == '\n' }) }

    @Test fun monthUsesBookZoneAndCustomIncludesEndDayAcrossDst() {
        val now = Instant.parse("2026-10-31T23:30:00Z")
        val month = CsvExportRequest(CsvExportScope.MONTH).period(ZoneId.of("Asia/Shanghai"), now)!!
        assertEquals(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli(), month.start)
        val custom = CsvExportRequest(CsvExportScope.CUSTOM, "2026-03-08", "2026-03-08").period(ZoneId.of("America/New_York"), now)!!
        assertEquals(23 * 60 * 60 * 1000L, custom.end - custom.start)
        assertNull(CsvExportRequest(CsvExportScope.ALL).period(ZoneId.of("UTC"), now))
    }
    @Test fun invalidAndReversedDatesAreRejected() {
        listOf("2026-02-30" to "2026-03-01", "2026-10-02" to "2026-10-01", "bad" to "2026-10-01").forEach { (start, end) ->
            assertThrows(IllegalArgumentException::class.java) { CsvExportRequest(CsvExportScope.CUSTOM, start, end).period(ZoneId.of("UTC")) }
        }
    }
}
