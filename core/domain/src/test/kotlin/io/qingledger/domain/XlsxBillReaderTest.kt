package io.qingledger.domain

import io.qingledger.domain.importing.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class XlsxBillReaderTest {
    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } } }.toByteArray()
    @Test fun supportsSharedInlineStringsAndNumericCellsWithoutEvaluatingFormulas() {
        val bytes = zip("xl/workbook.xml" to "<workbook><workbookPr date1904=\"1\"/></workbook>", "xl/sharedStrings.xml" to "<sst><si><r><t>交易</t></r><r><t>时间</t></r></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"18\"><c r=\"A18\" t=\"s\"><v>0</v></c><c r=\"C18\" t=\"inlineStr\"><is><t>商户</t></is></c></row><row r=\"19\"><c r=\"A19\"><v>46311.5</v></c><c r=\"B19\"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>")
        val table = XlsxBillReader.read(bytes)
        assertTrue(table.date1904); assertEquals(18, table.rows.first().line)
        assertEquals(listOf("交易时间", "", "商户"), table.rows.first().cells)
        assertTrue(0 in table.rows.last().numeric); assertTrue(1 in table.rows.last().formulas)
    }
    @Test fun rejectsDtdMissingSheetInvalidStringIndexAndMultipleSheets() {
        assertThrows(IllegalArgumentException::class.java) { XlsxBillReader.read(zip("xl/workbook.xml" to "<workbook/>")) }
        assertThrows(IllegalArgumentException::class.java) { XlsxBillReader.read(zip("xl/workbook.xml" to "<!DOCTYPE x [<!ENTITY a 'boom'>]><workbook>&a;</workbook>", "xl/worksheets/sheet1.xml" to "<worksheet/>")) }
        assertThrows(IllegalArgumentException::class.java) { XlsxBillReader.read(zip("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to "<worksheet><row r=\"1\"><c r=\"A1\" t=\"s\"><v>1</v></c></row></worksheet>")) }
        assertThrows(IllegalArgumentException::class.java) { XlsxBillReader.read(zip("xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to "<worksheet/>", "xl/worksheets/sheet2.xml" to "<worksheet/>")) }
    }
    @Test fun corruptZipIsRejected() { assertThrows(IllegalArgumentException::class.java) { XlsxBillReader.read("PKbroken".toByteArray()) } }
}
