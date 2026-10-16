package io.qingledger.domain.importing

import io.qingledger.domain.export.LedgerCsv
import io.qingledger.domain.model.*
import java.time.OffsetDateTime
import java.time.ZoneId

object CsvImportParser {
    fun parse(text: String): CsvParsedFile {
        require(text.isNotEmpty() && text.length <= CsvImportRepository.MAX_BYTES) { "CSV 文件为空或过大。" }
        val lines = readRecords(text.removePrefix("\uFEFF"))
        require(lines.isNotEmpty()) { "CSV 文件没有表头。" }
        val header = lines.first().second
        require(header in listOf(LedgerCsv.headers, LedgerCsv.v2Headers, LedgerCsv.baseHeaders)) { "请选择轻账导出的 CSV 文件，表头不匹配。" }
        val legacy = header == LedgerCsv.baseHeaders
        val records = mutableListOf<CsvImportRecord>()
        val issues = mutableListOf<CsvImportIssue>()
        lines.drop(1).forEach { (line, original) ->
            try {
                require(original.size == header.size) { "列数与表头不一致。" }
                val cells = original.toMutableList()
                if (!legacy) {
                    val metadata = cells.last()
                    val version = if (header == LedgerCsv.headers) "v3:" else "v2:"
                    require(metadata.startsWith(version)) { "文本转义标记无效。" }
                    val positions = metadata.removePrefix(version).split(';').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: throw IllegalArgumentException("文本转义字段无效。") }
                    require(positions.distinct().size == positions.size && positions.all { it in 0 until header.lastIndex }) { "文本转义字段无效。" }
                    positions.forEach { index ->
                        require(cells[index].startsWith("'") && LedgerCsv.needsProtection(cells[index].substring(1))) { "文本转义内容无效。" }
                        cells[index] = cells[index].substring(1)
                    }
                }
                val id = cells[0]
                require(id.isNotBlank() && id.length <= 128 && id == id.trim() && id.none { Character.isISOControl(it) }) { "账单 ID 无效。" }
                val kind = TransactionKind.entries.find { it.label == cells[3] } ?: throw IllegalArgumentException("收支类型无效。")
                val amount = LedgerRules.parseAmount(cells[4]) ?: throw IllegalArgumentException("金额无效，需为大于 0 的两位小数人民币金额。")
                require(cells[5] == "CNY") { "仅支持人民币 CNY。" }
                val date = OffsetDateTime.parse(cells[1])
                val zone = ZoneId.of(cells[2])
                require(date.year in 1..9999 && zone.rules.getOffset(date.toInstant()) == date.offset) { "交易时间与时区不匹配。" }
                require(cells[10].length <= 128 && cells[11].length <= 500) { "商户或备注超出长度限制。" }
                fun reference(index: Int) = cells[index].trim().also { require(it.isEmpty() || ReferenceRules.normalizeName(it) != null) { "分类或账户名称无效。" } }
                val category = reference(6); val account = reference(7); val from = reference(8); val to = reference(9)
                if (kind == TransactionKind.TRANSFER) require(category.isEmpty() && account.isEmpty() && from.isNotEmpty() && to.isNotEmpty() && !from.equals(to, true)) { "转账需要不同的转出和转入账户，且不能填写分类或收支账户。" }
                else require(from.isEmpty() && to.isEmpty()) { "收入或支出不能填写转账账户。" }
                require(cells[12].length <= 255 && cells[12].none { Character.isISOControl(it) }) { "来源应用无效。" }
                val originalId = if (header == LedgerCsv.headers) cells[13].takeIf { it.isNotEmpty() } else null
                require(originalId == null || (kind == TransactionKind.REFUND && originalId.length <= 128 && originalId == originalId.trim() && originalId.none { Character.isISOControl(it) })) { "退款关联 ID 无效。" }
                records += CsvImportRecord(line, id, kind, amount, date.toInstant().toEpochMilli(), zone.id,
                    cells[10], cells[11], category, account, from, to, cells[12], originalId)
            } catch (error: Exception) {
                issues += CsvImportIssue(line, if (error is IllegalArgumentException && !error.message.isNullOrBlank()) error.message!! else "日期、时区或字段格式无效。")
            }
        }
        return CsvParsedFile(records, issues, lines.size - 1, legacy)
    }

    internal fun readRecords(text: String): List<Pair<Int, List<String>>> {
        val result = mutableListOf<Pair<Int, List<String>>>()
        val fields = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var closed = false; var started = false; var index = 0; var line = 1; var recordLine = 1
        fun finishField() { fields += field.toString(); field.setLength(0); started = false; closed = false; require(fields.size <= 64) { "CSV 列数过多。" } }
        fun finishRecord() {
            finishField()
            if (fields.any { it.isNotEmpty() }) result += recordLine to fields.toList()
            fields.clear()
            require(result.size <= CsvImportRepository.MAX_ROWS + 1) { "CSV 超过 50000 行，请缩小导出范围。" }
        }
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') { field.append('"'); index++ }
                    else { quoted = false; closed = true }
                } else field.append(char)
                if (char == '\n' || (char == '\r' && text.getOrNull(index + 1) != '\n')) line++
            } else when (char) {
                '"' -> { require(!started && field.isEmpty() && !closed) { "第 $line 行引号格式错误。" }; quoted = true; started = true }
                ',' -> finishField()
                '\r', '\n' -> {
                    finishRecord()
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    line++; recordLine = line
                }
                else -> { require(!closed) { "第 $line 行引号后存在多余字符。" }; field.append(char); started = true }
            }
            require(field.length <= 65536) { "CSV 字段过长。" }
            index++
        }
        require(!quoted) { "CSV 引号未闭合。" }
        if (started || closed || fields.isNotEmpty() || field.isNotEmpty()) finishRecord()
        return result
    }
}
