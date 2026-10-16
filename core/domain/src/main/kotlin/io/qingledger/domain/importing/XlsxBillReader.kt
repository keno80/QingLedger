package io.qingledger.domain.importing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2

data class BillTableRow(val line: Int, val cells: List<String>, val numeric: Set<Int> = emptySet(), val formulas: Set<Int> = emptySet())
data class BillTable(val rows: List<BillTableRow>, val date1904: Boolean = false)

/** Bounded, read-only OOXML reader. No formulas, external links or embedded files are executed. */
object XlsxBillReader {
    fun read(bytes: ByteArray): BillTable {
        require(bytes.isNotEmpty() && bytes.size <= CsvImportRepository.MAX_BYTES) { "XLSX 文件为空或超过 16MB。" }
        try {
            val entries = mutableMapOf<String, ByteArray>()
            var total = 0; var count = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 256) { "XLSX 文件内容过多。" }
                    val name = entry.name
                    require(name !in entries && !name.contains("..") && !name.startsWith('/') && !name.contains('\\')) { "XLSX 文件结构无效。" }
                    val keep = name in setOf("xl/workbook.xml", "xl/sharedStrings.xml") || Regex("xl/worksheets/[^/]+\\.xml").matches(name)
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    var entrySize = 0
                    while (true) {
                        val size = zip.read(buffer)
                        if (size < 0) break
                        total += size; entrySize += size
                        require(total <= 64 * 1024 * 1024 && entrySize <= 16 * 1024 * 1024) { "XLSX 解压后内容过大。" }
                        if (keep) output.write(buffer, 0, size)
                    }
                    if (keep) entries[name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
            val workbook = entries["xl/workbook.xml"] ?: throw IllegalArgumentException("不是有效的 XLSX 文件。")
            var date1904 = false
            xml(workbook, object : Handler() { override fun start(name: String, attrs: Attributes) { if (name == "workbookPr") date1904 = attrs.getValue("date1904") in listOf("1", "true") } })
            val strings = mutableListOf<String>(); var inString = false; var inText = false; val text = StringBuilder()
            entries["xl/sharedStrings.xml"]?.let { shared -> xml(shared, object : Handler() {
                override fun start(name: String, attrs: Attributes) { if (name == "si") { inString = true; text.setLength(0) }; if (name == "t" && inString) inText = true }
                override fun chars(value: String) { if (inText) { text.append(value); require(text.length <= 65536) { "XLSX 字段过长。" } } }
                override fun end(name: String) { if (name == "t") inText = false; if (name == "si") { require(strings.size < 400000) { "XLSX 字符串过多。" }; strings += text.toString(); inString = false } }
            }) }
            val sheets = entries.filterKeys { it.startsWith("xl/worksheets/") }
            require(sheets.size == 1) { "请选择只包含一张平台账单工作表的 XLSX 文件。" }
            val rows = mutableListOf<BillTableRow>(); var line = 0; var column = 0; var type = ""; var reading = false; var formula = false
            val value = StringBuilder(); var cells = mutableMapOf<Int, String>(); var numeric = mutableSetOf<Int>(); var formulas = mutableSetOf<Int>()
            xml(sheets.values.single(), object : Handler() {
                override fun start(name: String, attrs: Attributes) {
                    when (name) {
                        "row" -> { line = attrs.getValue("r")?.toIntOrNull() ?: line + 1; require(line in 1..1000000); cells = mutableMapOf(); numeric = mutableSetOf(); formulas = mutableSetOf() }
                        "c" -> {
                            val ref = attrs.getValue("r") ?: throw IllegalArgumentException("XLSX 单元格位置缺失。")
                            val letters = ref.takeWhile { it in 'A'..'Z' }
                            require(letters.isNotEmpty() && letters.length <= 2) { "XLSX 列数过多。" }
                            column = letters.fold(0) { n, c -> n * 26 + (c - 'A' + 1) } - 1
                            require(column in 0..63 && column !in cells) { "XLSX 单元格位置重复或超限。" }
                            type = attrs.getValue("t").orEmpty(); formula = false; value.setLength(0)
                        }
                        "v", "t" -> reading = true
                        "f" -> formula = true
                    }
                }
                override fun chars(text: String) { if (reading) { value.append(text); require(value.length <= 65536) { "XLSX 字段过长。" } } }
                override fun end(name: String) {
                    when (name) {
                        "v", "t" -> reading = false
                        "c" -> {
                            val raw = value.toString()
                            cells[column] = when (type) { "s" -> strings.getOrNull(raw.toIntOrNull() ?: -1) ?: throw IllegalArgumentException("XLSX 字符串索引无效。"); "", "n", "str", "inlineStr" -> raw; else -> throw IllegalArgumentException("XLSX 含不支持的单元格类型。") }
                            if (type in setOf("", "n") && raw.isNotEmpty()) numeric += column
                            if (formula) formulas += column
                        }
                        "row" -> if (cells.isNotEmpty()) { require(rows.size < CsvImportRepository.MAX_ROWS + 200) { "XLSX 记录过多。" }; rows += BillTableRow(line, (0..cells.keys.max()).map { cells[it].orEmpty() }, numeric.toSet(), formulas.toSet()) }
                    }
                }
            })
            return BillTable(rows, date1904)
        } catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("XLSX 文件损坏或格式不支持，请重新导出。") }
    }
    private abstract class Handler : DefaultHandler2() {
        open fun start(name: String, attrs: Attributes) {}
        open fun end(name: String) {}
        open fun chars(value: String) {}
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) = start(localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty(), attributes)
        override fun endElement(uri: String?, localName: String?, qName: String?) = end(localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty())
        override fun characters(ch: CharArray, start: Int, length: Int) = chars(String(ch, start, length))
        override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw IllegalArgumentException("XLSX 不允许文档类型或外部实体。") }
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource { throw IllegalArgumentException("XLSX 不允许外部实体。") }
    }
    private fun xml(bytes: ByteArray, handler: Handler) {
        require(bytes.none { it == 0.toByte() }) { "XLSX XML 编码不支持。" }
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        reader.contentHandler = handler; reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }
}
