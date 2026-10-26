package io.qingledger.domain.importing

import io.qingledger.domain.model.*

data class CsvImportRecord(val line: Int, val id: String, val kind: TransactionKind, val amountMinor: Long,
    val occurredAt: Long, val zoneId: String, val merchant: String, val note: String, val category: String,
    val account: String, val fromAccount: String, val toAccount: String, val sourcePackage: String, val originalExpenseId: String? = null,
    val originalAmountMinor: Long? = null, val discountMinor: Long? = null)
data class CsvImportIssue(val line: Int, val message: String)
data class CsvParsedFile(val records: List<CsvImportRecord>, val issues: List<CsvImportIssue>, val totalRows: Int, val legacy: Boolean, val skipped: List<CsvImportIssue> = emptyList())
enum class ImportSource(val title: String, val hint: String) {
    QINGLEDGER("轻账 CSV", "UTF-8 CSV"), WECHAT("微信支付", "XLSX / UTF-8 CSV"), ALIPAY("支付宝", "GBK / UTF-8 CSV")
}
enum class CsvReferenceType { EXPENSE_CATEGORY, INCOME_CATEGORY, ACCOUNT }
enum class CsvMappingAction { UNRESOLVED, EXISTING, CREATE, RESTORE, NONE }
data class CsvMappingChoice(val action: CsvMappingAction, val id: String? = null)
data class CsvImportMapping(val key: String, val name: String, val type: CsvReferenceType, val required: Boolean,
    val choice: CsvMappingChoice, val error: String? = null)
enum class CsvImportStatus { READY, DUPLICATE, INVALID, NEEDS_MAPPING, SKIPPED }
data class CsvImportPreviewRow(val line: Int, val record: CsvImportRecord?, val status: CsvImportStatus, val message: String)
data class CsvImportPlan(val file: CsvParsedFile, val mappings: List<CsvImportMapping>, val rows: List<CsvImportPreviewRow>,
    val categories: List<Category>, val accounts: List<Account>) {
    val ready: Int get() = rows.count { it.status == CsvImportStatus.READY }
    val duplicates: Int get() = rows.count { it.status == CsvImportStatus.DUPLICATE }
    val invalid: Int get() = rows.count { it.status == CsvImportStatus.INVALID }
    val pending: Int get() = rows.count { it.status == CsvImportStatus.NEEDS_MAPPING }
    val skipped: Int get() = rows.count { it.status == CsvImportStatus.SKIPPED }
    val canImport: Boolean get() = ready > 0 && pending == 0
}
data class CsvImportResult(val imported: Int, val skipped: Int, val failed: Int)
interface CsvImportRepository {
    suspend fun preview(file: CsvParsedFile, choices: Map<String, CsvMappingChoice> = emptyMap()): CsvImportPlan
    suspend fun commit(plan: CsvImportPlan): CsvImportResult
    companion object { const val MAX_BYTES = 16 * 1024 * 1024; const val MAX_ROWS = 50_000 }
}
