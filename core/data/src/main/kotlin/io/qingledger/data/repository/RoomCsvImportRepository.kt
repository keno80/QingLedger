package io.qingledger.data.repository

import androidx.room.withTransaction
import io.qingledger.data.database.*
import io.qingledger.domain.importing.*
import io.qingledger.domain.model.*
import java.time.Clock
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomCsvImportRepository(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) : CsvImportRepository {
    private val dao = database.ledgerDao()
    private data class Request(val name: String, val type: CsvReferenceType, val required: Boolean) {
        val key: String get() = type.name + ":" + name.lowercase(Locale.ROOT)
    }
    private fun requests(row: CsvImportRecord): List<Request> = buildList {
        if (row.kind != TransactionKind.TRANSFER) {
            if (row.category.isNotEmpty()) add(Request(row.category, if (row.kind.categoryKind == TransactionKind.EXPENSE) CsvReferenceType.EXPENSE_CATEGORY else CsvReferenceType.INCOME_CATEGORY, false))
            if (row.account.isNotEmpty()) add(Request(row.account, CsvReferenceType.ACCOUNT, false))
        } else {
            add(Request(row.fromAccount, CsvReferenceType.ACCOUNT, true)); add(Request(row.toAccount, CsvReferenceType.ACCOUNT, true))
        }
    }
    override suspend fun preview(file: CsvParsedFile, choices: Map<String, CsvMappingChoice>): CsvImportPlan = withContext(Dispatchers.IO) {
        database.withTransaction { plan(file, choices) }
    }
    private suspend fun plan(file: CsvParsedFile, choices: Map<String, CsvMappingChoice>): CsvImportPlan {
        require(file.totalRows <= CsvImportRepository.MAX_ROWS) { "CSV 超过 50000 行。" }
        val categories = dao.allCategories().map { Category(it.id, it.name, TransactionKind.valueOf(it.kind), it.archived) }
        val accounts = dao.allAccounts().map { Account(it.id, it.name, it.archived, it.openingBalanceMinor) }
        val existing = file.records.map { it.id }.distinct().chunked(500).flatMap { dao.existingImportIds(it) }.toSet()
        val conflicts = file.records.groupBy { it.id }.filterValues { records -> records.distinctBy { it.copy(line = 0) }.size > 1 }.keys
        val seen = mutableSetOf<String>()
        val preview = file.issues.map { CsvImportPreviewRow(it.line, null, CsvImportStatus.INVALID, it.message) }.toMutableList()
        preview += file.skipped.map { CsvImportPreviewRow(it.line, null, CsvImportStatus.SKIPPED, it.message) }
        val candidates = file.records.filter { row ->
            val status = when {
                row.id in existing -> CsvImportStatus.DUPLICATE
                row.id in conflicts -> CsvImportStatus.INVALID
                !seen.add(row.id) -> CsvImportStatus.DUPLICATE
                else -> null
            }
            if (status != null) preview += CsvImportPreviewRow(row.line, row, status, if (status == CsvImportStatus.DUPLICATE) "账单 ID 已存在，保留原数据。" else "文件内相同 ID 的内容不一致。")
            status == null
        }
        val grouped = candidates.flatMap(::requests).groupBy { it.key }
        val mappings = grouped.map { (key, entries) ->
            val request = entries.first(); val required = entries.any { it.required }
            val kind = if (request.type == CsvReferenceType.EXPENSE_CATEGORY) TransactionKind.EXPENSE else TransactionKind.INCOME
            val targets = if (request.type == CsvReferenceType.ACCOUNT) accounts.map { Triple(it.id, it.name, it.archived) }
                else categories.filter { it.kind == kind }.map { Triple(it.id, it.name, it.archived) }
            val match = targets.singleOrNull { it.second.equals(request.name, true) && !it.third }
            val choice = choices[key] ?: if (match == null) CsvMappingChoice(CsvMappingAction.UNRESOLVED) else CsvMappingChoice(CsvMappingAction.EXISTING, match.first)
            val target = targets.find { it.first == choice.id }
            val error = when (choice.action) {
                CsvMappingAction.UNRESOLVED -> "请选择匹配项。"
                CsvMappingAction.CREATE -> if (targets.any { it.second.equals(request.name, true) }) "名称已存在，请使用或恢复已有项目。" else null
                CsvMappingAction.EXISTING -> if (target == null || target.third) "匹配项目已变化，请重新选择。" else null
                CsvMappingAction.RESTORE -> if (target == null || !target.third) "归档状态已变化，请重新选择。" else null
                CsvMappingAction.NONE -> if (required) "转账账户不能为空。" else null
            }
            CsvImportMapping(key, request.name, request.type, required, choice, error)
        }
        val byKey = mappings.associateBy { it.key }
        candidates.forEach { row ->
            val references = requests(row)
            val unresolved = references.any { byKey.getValue(it.key).error != null }
            val problem = if (unresolved) null else LedgerRules.validate(draft(row) { key ->
                val mapping = byKey.getValue(key)
                when (mapping.choice.action) {
                    CsvMappingAction.NONE -> null
                    CsvMappingAction.CREATE -> "create:${mapping.key}"
                    else -> "existing:${mapping.choice.id}"
                }
            })
            preview += CsvImportPreviewRow(row.line, row, when { unresolved -> CsvImportStatus.NEEDS_MAPPING; problem != null -> CsvImportStatus.INVALID; else -> CsvImportStatus.READY },
                when { unresolved -> "请先匹配分类或账户。"; problem != null -> problem; else -> "可导入" })
        }
        val live = dao.allTransactionsForBackup().filter { it.deletedAt == null }.map { it.domain() }
        val proposed = preview.filter { it.status == CsvImportStatus.READY }.map { item ->
            val row = item.record!!
            val draft = draft(row) { key ->
                val choice = byKey.getValue(key).choice
                when (choice.action) { CsvMappingAction.NONE -> null; CsvMappingAction.CREATE -> "create:$key"; else -> choice.id }
            }
            LedgerTransaction(row.id, row.kind, row.amountMinor, row.occurredAt, row.merchant, draft.categoryId, draft.accountId, draft.fromAccountId, draft.toAccountId, row.note, 0, row.zoneId, row.sourcePackage, row.originalExpenseId)
        }
        val all = live + proposed
        val byId = all.associateBy { it.id }
        val refundsByOriginal = all.filter { it.kind == TransactionKind.REFUND && it.originalExpenseId != null }.groupBy { it.originalExpenseId }
        val problems = mutableMapOf<String, String?>()
        proposed.filter { it.kind == TransactionKind.REFUND && it.originalExpenseId != null }.groupBy { it.originalExpenseId }.forEach { (originalId, refunds) ->
            val problem = RefundRules.validate(listOfNotNull(byId[originalId]) + refundsByOriginal[originalId].orEmpty())
            refunds.forEach { problems[it.id] = problem }
        }
        val checked = preview.map { item -> problems[item.record?.id]?.let { item.copy(status = CsvImportStatus.INVALID, message = it) } ?: item }
        return CsvImportPlan(file, mappings, checked.sortedBy { it.line }, categories, accounts)
    }
    private fun draft(row: CsvImportRecord, resolve: (String) -> String?): TransactionDraft {
        fun id(name: String, type: CsvReferenceType): String? = if (name.isEmpty()) null else resolve(Request(name, type, false).key)
        return TransactionDraft(row.kind, row.amountMinor, row.occurredAt, row.merchant,
            categoryId = if (row.kind == TransactionKind.TRANSFER) null else id(row.category, if (row.kind.categoryKind == TransactionKind.EXPENSE) CsvReferenceType.EXPENSE_CATEGORY else CsvReferenceType.INCOME_CATEGORY),
            accountId = id(row.account, CsvReferenceType.ACCOUNT), fromAccountId = id(row.fromAccount, CsvReferenceType.ACCOUNT), toAccountId = id(row.toAccount, CsvReferenceType.ACCOUNT), note = row.note, originalExpenseId = row.originalExpenseId)
    }
    override suspend fun commit(plan: CsvImportPlan): CsvImportResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val fresh = plan(plan.file, plan.mappings.associate { it.key to it.choice })
            check(fresh.pending == 0) { "分类或账户已变化，请重新预览并匹配。" }
            val ready = fresh.rows.filter { it.status == CsvImportStatus.READY }.map { it.record!! }
            val used = ready.flatMap(::requests).map { it.key }.toSet()
            val ids = mutableMapOf<String, String?>()
            fresh.mappings.filter { it.key in used }.forEach { mapping ->
                val choice = mapping.choice
                ids[mapping.key] = when (choice.action) {
                    CsvMappingAction.NONE -> null
                    CsvMappingAction.EXISTING -> choice.id!!
                    CsvMappingAction.RESTORE -> choice.id!!.also { if (mapping.type == CsvReferenceType.ACCOUNT) dao.restoreAccount(it) else dao.restoreCategory(it) }
                    CsvMappingAction.CREATE -> UUID.randomUUID().toString().also { id ->
                        if (mapping.type == CsvReferenceType.ACCOUNT) dao.addAccount(AccountEntity(id, "default", mapping.name))
                        else dao.addCategory(CategoryEntity(id, "default", mapping.name, if (mapping.type == CsvReferenceType.EXPENSE_CATEGORY) "EXPENSE" else "INCOME"))
                    }
                    CsvMappingAction.UNRESOLVED -> error("分类或账户未匹配。")
                }
            }
            val now = clock.millis()
            ready.forEach { row ->
                val draft = draft(row) { ids[it] }
                check(LedgerRules.validate(draft) == null) { "导入内容校验失败。" }
                dao.insert(TransactionEntity(row.id, "default", row.kind.name, row.amountMinor, "CNY", row.occurredAt, row.zoneId, row.merchant,
                    draft.categoryId, draft.accountId, draft.fromAccountId, draft.toAccountId, row.note, 0, now, now, sourcePackage = row.sourcePackage.takeIf { it.isNotBlank() }, originalExpenseId = row.originalExpenseId))
                dao.log(EditEntity(UUID.randomUUID().toString(), row.id, 0, "IMPORT_CSV", now))
            }
            RefundRules.validate(dao.allTransactionsForBackup().filter { it.deletedAt == null }.map { it.domain() })?.let { throw IllegalStateException(it) }
            CsvImportResult(ready.size, fresh.duplicates + fresh.skipped, fresh.invalid)
        }
    }
    private fun TransactionEntity.domain() = LedgerTransaction(id, TransactionKind.valueOf(kind), amountMinor, occurredAt, merchant, categoryId, accountId, fromAccountId, toAccountId, note, revision, zoneId, sourcePackage, originalExpenseId)
}
