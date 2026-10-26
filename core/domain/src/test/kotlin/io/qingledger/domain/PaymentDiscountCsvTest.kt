package io.qingledger.domain

import io.qingledger.domain.export.*
import io.qingledger.domain.importing.*
import io.qingledger.domain.model.*
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class PaymentDiscountCsvTest {
    private val row = LedgerCsvRow("discount-test", TransactionKind.EXPENSE, 760, 0, "Asia/Shanghai", "CNY", "", "", "", "", "=测试商户", "", "", originalAmountMinor = 800, discountMinor = 40)
    private fun csv(value: LedgerCsvRow) = StringWriter().also { LedgerCsv.write(it, listOf(value)) }.toString()
    @Test fun roundTripPreservesAuxiliaryPricesAndFormulaProtection() {
        val parsed = CsvImportParser.parse(csv(row))
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals(800L, parsed.records.single().originalAmountMinor)
        assertEquals(40L, parsed.records.single().discountMinor)
        assertEquals(row.merchant, parsed.records.single().merchant)
        assertEquals(760L, parsed.records.single().amountMinor)
    }
    @Test fun unknownAndZeroRemainDifferentAndInvalidPairIsRejected() {
        assertNull(CsvImportParser.parse(csv(row.copy(originalAmountMinor = null, discountMinor = null))).records.single().discountMinor)
        assertEquals(0L, CsvImportParser.parse(csv(row.copy(originalAmountMinor = 760, discountMinor = 0))).records.single().discountMinor)
        assertEquals(1, CsvImportParser.parse(csv(row.copy(discountMinor = 50))).issues.size)
        assertEquals(1, CsvImportParser.parse(csv(row.copy(kind = TransactionKind.INCOME))).issues.size)
    }
    @Test fun previousV3FormatKeepsRefundLinkAndDoesNotInventPrices() {
        val input = csv(row.copy(kind = TransactionKind.REFUND, merchant = "测试", originalExpenseId = "old-expense", originalAmountMinor = null, discountMinor = null))
            .replace("\"原价（元）\",\"优惠（元）\",", "")
            .replace(",\"\",\"\",\"v4:", ",\"v3:")
        val parsed = CsvImportParser.parse(input)
        assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
        assertEquals("old-expense", parsed.records.single().originalExpenseId)
        assertNull(parsed.records.single().originalAmountMinor)
        assertNull(parsed.records.single().discountMinor)
    }
}
