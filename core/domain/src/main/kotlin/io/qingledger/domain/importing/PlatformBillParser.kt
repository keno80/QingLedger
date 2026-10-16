package io.qingledger.domain.importing

import io.qingledger.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object PlatformBillParser {
    private val timeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val wxHeaders = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注")
    private val aliHeaders = listOf("交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支", "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号", "备注")

    fun parse(bytes: ByteArray, source: ImportSource): CsvParsedFile {
        require(bytes.isNotEmpty() && bytes.size <= CsvImportRepository.MAX_BYTES) { "文件为空或超过 16MB。" }
        require(source != ImportSource.QINGLEDGER) { "请选择微信或支付宝来源。" }
        val zipped = bytes.size >= 4 && bytes[0] == 80.toByte() && bytes[1] == 75.toByte()
        val table = if (zipped) {
            require(source == ImportSource.WECHAT) { "支付宝请选择导出的 CSV 文件。" }
            XlsxBillReader.read(bytes)
        } else {
            val text = decode(bytes, source).removePrefix("\uFEFF")
            val lines = text.split('\n')
            val start = lines.take(200).indexOfFirst { it.trimStart().startsWith("交易时间,") || it.trimStart().startsWith("\"交易时间\",") }
            require(start >= 0) { "未找到平台账单表头，请选择原始导出文件。" }
            BillTable(CsvImportParser.readRecords(lines.drop(start).joinToString("\n")).map { (line, cells) -> BillTableRow(line + start, cells) })
        }
        return parseTable(table, source)
    }
    private fun decode(bytes: ByteArray, source: ImportSource): String {
        fun decoded(charset: Charset) = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return try { decoded(Charsets.UTF_8) } catch (_: java.nio.charset.CharacterCodingException) {
            require(source == ImportSource.ALIPAY) { "微信 CSV 需为 UTF-8 编码，也可选择 XLSX 文件。" }
            try { decoded(Charset.forName("GB18030")) } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("文件编码无效，请使用支付宝原始 CSV。") }
        }
    }
    private fun clean(value: String) = value.trim().takeUnless { it == "/" || it == "-" }.orEmpty()
    private data class Parsed(val record: CsvImportRecord, val order: String)
    private fun parseTable(table: BillTable, source: ImportSource): CsvParsedFile {
        val expected = if (source == ImportSource.WECHAT) wxHeaders else aliHeaders
        fun normalized(cells: List<String>) = cells.map { it.trim().replace('（', '(').replace('）', ')') }
        val headerIndex = table.rows.take(200).indexOfFirst { normalized(it.cells).containsAll(expected) }
        require(headerIndex >= 0) { "账单表头与所选平台不符。" }
        val header = normalized(table.rows[headerIndex].cells)
        require(expected.all { name -> header.count { it == name } == 1 }) { "账单包含重复字段。" }
        val rows = table.rows.drop(headerIndex + 1).filter { it.cells.any { cell -> cell.isNotBlank() } }
        require(rows.size <= CsvImportRepository.MAX_ROWS) { "账单超过 50000 笔，请缩小导出范围。" }
        val parsed = mutableListOf<Parsed>(); val issues = mutableListOf<CsvImportIssue>(); val skipped = mutableListOf<CsvImportIssue>()
        for (row in rows) {
            try {
                require(row.formulas.isEmpty()) { "账单含公式，请使用平台原始导出文件。" }
                fun cell(name: String) = clean(row.cells.getOrElse(header.indexOf(name)) { "" })
                val wx = source == ImportSource.WECHAT
                val status = cell(if (wx) "当前状态" else "交易状态")
                val type = cell(if (wx) "交易类型" else "交易分类")
                val direction = cell("收/支")
                val isRefund = if (wx) direction == "收入" && (type.contains("退款") || status.contains("退款")) else status == "退款成功"
                val allowed = if (wx) status in setOf("支付成功", "对方已收钱", "已转账", "已存入零钱", "已收钱", "已收款", "已全额退款", "等待对方确认收钱", "对方已退还") || status.startsWith("已退款")
                    else status in setOf("支付成功", "交易成功", "等待确认收货", "退款成功") || (status == "交易关闭" && direction == "支出" && cell("收/付款方式").isNotBlank())
                if (!allowed || (!isRefund && direction !in setOf("支出", "收入"))) {
                    skipped += CsvImportIssue(row.line, "已跳过：${status.ifBlank { "状态未知" }}${if (direction.isBlank()) "" else " · $direction"}")
                    continue
                }
                val idColumn = if (wx) "交易单号" else "交易订单号"
                val externalId = cell(idColumn)
                require(externalId.isNotBlank() && externalId.length <= 128 && externalId.none { Character.isISOControl(it) } && !Regex("[0-9]+(?:\\.[0-9]+)?[eE][+-]?[0-9]+").matches(externalId)) { "交易单号缺失或格式无效。" }
                require(header.indexOf(idColumn) !in row.numeric || externalId.length <= 15) { "交易单号已转成数值，可能丢失精度，请使用原始文件。" }
                val amountText = cell(if (wx) "金额(元)" else "金额").removePrefix("¥").removePrefix("￥").trim()
                val amount = LedgerRules.parseAmount(amountText) ?: throw IllegalArgumentException("金额无效，需为大于 0 的人民币金额。")
                val kind = if (isRefund) TransactionKind.REFUND else if (direction == "收入") TransactionKind.INCOME else TransactionKind.EXPENSE
                val dateCell = cell("交易时间")
                val local = if (header.indexOf("交易时间") in row.numeric) excelTime(dateCell, table.date1904) else LocalDateTime.parse(dateCell, timeFormat)
                require(local.year in 1900..9999) { "交易时间超出范围。" }
                val merchant = cell("交易对方")
                val product = cell(if (wx) "商品" else "商品说明")
                val method = cell(if (wx) "支付方式" else "收/付款方式")
                val account = account(method, source)
                val note = listOfNotNull(product.takeIf { it.isNotBlank() }?.let { "商品：$it" }, cell("备注").takeIf { it.isNotBlank() }, "状态：$status", method.takeIf { it.isNotBlank() }?.let { "付款方式：$it" }).joinToString("\n")
                require(merchant.length <= 128 && note.length <= 500) { "商户或备注过长，请在导入前检查。" }
                val category = if (isRefund) "退款" else if (wx) "" else type
                require(category.isEmpty() || ReferenceRules.normalizeName(category) != null) { "分类名称无效。" }
                require(account.isEmpty() || ReferenceRules.normalizeName(account) != null) { "付款方式无法映射为账户，请检查原始字段。" }
                parsed += Parsed(CsvImportRecord(row.line, stableId(source, externalId), kind, amount, local.atZone(zone).toInstant().toEpochMilli(), zone.id,
                    merchant, note, category, account, "", "", if (wx) "com.tencent.mm" else "com.eg.android.AlipayGphone"), cell(if (wx) "商户单号" else "商家订单号"))
            } catch (error: Exception) {
                issues += CsvImportIssue(row.line, if (error is IllegalArgumentException && !error.message.isNullOrBlank()) error.message!! else "日期或字段格式无效。")
            }
        }
        val originals = parsed.filter { it.record.kind == TransactionKind.EXPENSE && it.order.isNotBlank() }.groupBy { it.order }
        val records = parsed.map { item ->
            val row = item.record
            val original = originals[item.order]?.singleOrNull()?.record
            if (row.kind == TransactionKind.REFUND && original != null && original.occurredAt <= row.occurredAt && original.merchant == row.merchant)
                row.copy(originalExpenseId = original.id, category = original.category)
            else row
        }
        return CsvParsedFile(records, issues, rows.size, false, skipped)
    }
    fun stableId(source: ImportSource, transactionId: String): String = source.name.lowercase() + ":" + MessageDigest.getInstance("SHA-256")
        .digest(transactionId.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun account(raw: String, source: ImportSource): String {
        if (raw.isBlank()) return ""
        val parts = raw.split('&').map { it.trim() }
        require(parts.drop(1).none { PaymentAccountRules.canonicalName(it) != null || it in setOf("余额", "支付宝余额", "余额宝", "花呗") }) { "组合付款包含多个资金账户，当前不支持自动拆分。" }
        val first = raw.substringBefore('&').trim()
        return PaymentAccountRules.canonicalName(first) ?: when (first) {
            "余额", "支付宝余额" -> if (source == ImportSource.ALIPAY) "支付宝余额" else first
            "余额宝" -> "余额宝"
            "花呗" -> "花呗"
            else -> first
        }
    }
    private fun excelTime(raw: String, date1904: Boolean): LocalDateTime {
        val serial = BigDecimal(raw)
        require(serial >= BigDecimal.ONE && (date1904 || serial.toLong() != 60L)) { "Excel 日期无效。" }
        val seconds = serial.multiply(BigDecimal(86400)).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val base = if (date1904) LocalDate.of(1904, 1, 1) else if (serial < BigDecimal(60)) LocalDate.of(1899, 12, 31) else LocalDate.of(1899, 12, 30)
        return base.atStartOfDay().plusSeconds(seconds)
    }
}
