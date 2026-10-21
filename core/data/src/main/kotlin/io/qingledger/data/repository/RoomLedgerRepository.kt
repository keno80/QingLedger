package io.qingledger.data.repository

import androidx.room.withTransaction
import io.qingledger.data.database.*
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomLedgerRepository(private val database: LedgerDatabase, private val clock: Clock = Clock.systemUTC()) : LedgerRepository {
    private val dao = database.ledgerDao()
    override suspend fun initialize() = database.withTransaction {
        dao.seedBook(BookEntity("default", "日常账本", "CNY", ZoneId.systemDefault().id))
        dao.seedCategories(listOf("餐饮", "交通", "购物", "居住", "娱乐", "医疗", "未分类").mapIndexed { index, name -> CategoryEntity("expense-$index", "default", name, "EXPENSE") } +
            listOf("工资", "奖金", "其他收入").mapIndexed { index, name -> CategoryEntity("income-$index", "default", name, "INCOME") })
        dao.seedAccounts(listOf("现金", "银行卡", "微信零钱", "支付宝余额").mapIndexed { index, name -> AccountEntity("account-$index", "default", name) })
    }
    override suspend fun bookZoneId() = dao.book().zoneId
    override val categories = dao.categories().map { rows -> rows.map { Category(it.id, it.name, TransactionKind.valueOf(it.kind), it.archived) } }
    override val accounts = dao.accounts().map { rows -> rows.map { Account(it.id, it.name, it.archived, it.openingBalanceMinor) } }
    override fun accountBalances() = dao.accountBalances().map { rows -> rows.map { AccountBalance(Account(it.id, it.name, it.archived, it.openingBalanceMinor), it.movementMinor) } }
    override suspend fun saveAccountDetails(name: String, openingBalanceMinor: Long?, id: String?) = database.withTransaction {
        require(openingBalanceMinor == null || openingBalanceMinor in -LedgerRules.MAX_AMOUNT..LedgerRules.MAX_AMOUNT) { "初始余额超出范围。" }
        val savedId = saveAccount(name, id)
        dao.setOpeningBalance(savedId, openingBalanceMinor)
        savedId
    }
    override fun transactions(filter: LedgerFilter) = dao.transactions(filter.period.start, filter.period.end, filter.query, filter.kind?.name, filter.categoryId, filter.accountId, filter.limit.coerceIn(1, 10000)).map { rows -> rows.map { it.domain() } }
    override fun summary(period: PeriodRange) = dao.summary(period.start, period.end).map { LedgerSummary(it.income, it.expense) }
    override fun monthlyTransactions(period: PeriodRange) = dao.monthlyTransactions(period.start, period.end).map { rows -> rows.map { it.domain() } }
    override fun statisticsTransactions(period: PeriodRange?) = dao.statisticsTransactions(period?.start, period?.end).map { rows -> rows.map { it.domain() } }
    override fun overview(zoneId: String) = dao.recordedTimes().map { times ->
        val zone = ZoneId.of(zoneId)
        LedgerOverview(times.size, times.map { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSet().size)
    }
    override suspend fun get(id: String) = dao.get(id)?.takeIf { it.deletedAt == null }?.domain()
    override fun changes(id: String) = dao.edits(id).map { rows -> rows.map { TransactionChange(it.revision, it.operation, it.createdAt) } }

    override suspend fun saveCategory(name: String, kind: TransactionKind, id: String?): String = database.withTransaction {
        val normalized = ReferenceRules.normalizeName(name) ?: throw IllegalArgumentException("名称需为 1–32 个字符，不能包含换行或控制字符。")
        require(kind in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME)) { "分类只能用于收入或支出。" }
        val previous = id?.let { dao.category(it) ?: throw IllegalArgumentException("分类不存在。") }
        require(previous == null || (!previous.archived && previous.kind == kind.name)) { "不能修改归档分类或分类收支类型。" }
        require(dao.allCategories().none { it.id != id && it.kind == kind.name && it.name.equals(normalized, ignoreCase = true) }) { "同类分类名称已存在；已归档分类可直接恢复。" }
        val resultId = id ?: UUID.randomUUID().toString()
        if (previous == null) dao.addCategory(CategoryEntity(resultId, "default", normalized, kind.name)) else dao.renameCategory(resultId, normalized)
        resultId
    }
    override suspend fun saveAccount(name: String, id: String?): String = database.withTransaction {
        val normalized = ReferenceRules.normalizeName(name) ?: throw IllegalArgumentException("名称需为 1–32 个字符，不能包含换行或控制字符。")
        val previous = id?.let { dao.account(it) ?: throw IllegalArgumentException("账户不存在。") }
        require(previous == null || !previous.archived) { "请先恢复已归档账户。" }
        require(dao.allAccounts().none { it.id != id && it.name.equals(normalized, ignoreCase = true) }) { "账户名称已存在；已归档账户可直接恢复。" }
        val resultId = id ?: UUID.randomUUID().toString()
        if (previous == null) dao.addAccount(AccountEntity(resultId, "default", normalized)) else dao.renameAccount(resultId, normalized)
        resultId
    }
    override suspend fun restoreCategory(id: String) = database.withTransaction {
        requireNotNull(dao.category(id)) { "分类不存在。" }
        dao.restoreCategory(id)
    }
    override suspend fun restoreAccount(id: String) = database.withTransaction {
        requireNotNull(dao.account(id)) { "账户不存在。" }
        dao.restoreAccount(id)
    }

    override suspend fun save(draft: TransactionDraft, id: String?, expectedRevision: Int?): String = database.withTransaction {
        LedgerRules.validate(draft)?.let { throw IllegalArgumentException(it) }
        val previous = id?.let { dao.get(it) ?: throw LedgerConflict() }
        if (previous != null && (previous.revision != expectedRevision || previous.deletedAt != null)) throw LedgerConflict()
        draft.categoryId?.let { categoryId ->
            val category = dao.category(categoryId) ?: throw IllegalArgumentException("分类不存在。")
            require(category.bookId == "default" && category.kind == draft.kind.categoryKind.name) { "分类与收支类型不匹配。" }
            require(!category.archived || categoryId == previous?.categoryId) { "分类已归档，请重新选择。" }
        }
        listOfNotNull(draft.accountId, draft.fromAccountId, draft.toAccountId).forEach { accountId ->
            val account = dao.account(accountId) ?: throw IllegalArgumentException("账户不存在。")
            require(account.bookId == "default") { "账户不属于当前账本。" }
            require(!account.archived || accountId in listOf(previous?.accountId, previous?.fromAccountId, previous?.toAccountId)) { "账户已归档，请重新选择。" }
        }
        val now = clock.millis()
        val transaction = TransactionEntity(
            id = id ?: UUID.randomUUID().toString(), bookId = "default", kind = draft.kind.name,
            amountMinor = draft.amountMinor, currency = "CNY", occurredAt = draft.occurredAt,
            zoneId = previous?.zoneId ?: dao.book().zoneId, merchant = draft.merchant.trim(),
            categoryId = draft.categoryId, accountId = draft.accountId,
            fromAccountId = draft.fromAccountId, toAccountId = draft.toAccountId, note = draft.note.trim(),
            revision = (previous?.revision ?: -1) + 1, createdAt = previous?.createdAt ?: now, updatedAt = now,
            sourcePackage = previous?.sourcePackage,
            originalExpenseId = draft.originalExpenseId,
        )
        validateRefunds(transaction)
        if (previous == null) dao.insert(transaction) else dao.update(transaction)
        val changed = if (previous == null) emptyList() else buildList {
            if (previous.amountMinor != transaction.amountMinor) add("金额")
            if (previous.kind != transaction.kind) add("收支类型")
            if (previous.merchant != transaction.merchant) add("商户")
            if (previous.categoryId != transaction.categoryId) add("分类")
            if (previous.accountId != transaction.accountId || previous.fromAccountId != transaction.fromAccountId || previous.toAccountId != transaction.toAccountId) add("账户")
            if (previous.occurredAt != transaction.occurredAt) add("时间")
            if (previous.note != transaction.note) add("备注")
            if (previous.originalExpenseId != transaction.originalExpenseId) add("退款关联")
        }
        val operation = if (previous == null) "CREATE" else if (changed.isEmpty()) "EDIT" else "EDIT:" + changed.joinToString("、")
        dao.log(EditEntity(UUID.randomUUID().toString(), transaction.id, transaction.revision, operation, now))
        transaction.id
    }

    override suspend fun delete(id: String, expectedRevision: Int): UndoDeletion = database.withTransaction {
        val row = dao.get(id) ?: throw LedgerConflict()
        if (row.deletedAt != null || row.revision != expectedRevision) throw LedgerConflict()
        require(dao.allTransactionsForBackup().none { it.deletedAt == null && it.originalExpenseId == id }) { "请先删除关联退款或解除退款关联，再删除原支出。" }
        val now = clock.millis()
        val revision = row.revision + 1
        dao.update(row.copy(deletedAt = now, updatedAt = now, revision = revision))
        dao.log(EditEntity(UUID.randomUUID().toString(), id, revision, "DELETE", now))
        UndoDeletion(id, revision, now + 10000)
    }
    override suspend fun undo(token: UndoDeletion) = database.withTransaction {
        require(clock.millis() <= token.expiresAt) { "撤销时间已过。" }
        val row = dao.get(token.id) ?: throw LedgerConflict()
        if (row.deletedAt == null || row.revision != token.revision) throw LedgerConflict()
        validateRefunds(row.copy(deletedAt = null))
        dao.update(row.copy(deletedAt = null, updatedAt = clock.millis(), revision = row.revision + 1))
        dao.log(EditEntity(UUID.randomUUID().toString(), row.id, row.revision + 1, "RESTORE", clock.millis()))
    }
    override suspend fun archiveCategory(id: String) = database.withTransaction { dao.archiveCategory(id) }
    override suspend fun archiveAccount(id: String) = database.withTransaction { dao.archiveAccount(id) }
    private suspend fun validateRefunds(row: TransactionEntity) {
        val live = dao.allTransactionsForBackup().filter { it.deletedAt == null && it.id != row.id } + row
        RefundRules.validate(live.map { it.domain() })?.let { throw IllegalArgumentException(it) }
    }
    private fun TransactionEntity.domain() = LedgerTransaction(id, TransactionKind.valueOf(kind), amountMinor, occurredAt, merchant, categoryId, accountId, fromAccountId, toAccountId, note, revision, zoneId, sourcePackage, originalExpenseId)
}
