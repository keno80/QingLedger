package io.qingledger.domain.repository

import io.qingledger.domain.model.*
import kotlinx.coroutines.flow.Flow

data class LedgerFilter(
    val period: PeriodRange,
    val query: String = "",
    val kind: TransactionKind? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val limit: Int = 50,
)
data class UndoDeletion(val id: String, val revision: Int, val expiresAt: Long)
data class LedgerOverview(val recordedCount: Int = 0, val recordedDays: Int = 0)
class LedgerConflict : IllegalStateException("账单已被修改，请返回列表重新打开。")

interface LedgerRepository {
    suspend fun initialize()
    suspend fun bookZoneId(): String
    val categories: Flow<List<Category>>
    val accounts: Flow<List<Account>>
    fun transactions(filter: LedgerFilter): Flow<List<LedgerTransaction>>
    fun summary(period: PeriodRange): Flow<LedgerSummary>
    fun overview(zoneId: String): Flow<LedgerOverview>
    suspend fun get(id: String): LedgerTransaction?
    fun changes(id: String): Flow<List<TransactionChange>>
    suspend fun saveCategory(name: String, kind: TransactionKind, id: String? = null): String
    suspend fun saveAccount(name: String, id: String? = null): String
    suspend fun restoreCategory(id: String)
    suspend fun restoreAccount(id: String)
    suspend fun save(draft: TransactionDraft, id: String? = null, expectedRevision: Int? = null): String
    suspend fun delete(id: String, expectedRevision: Int): UndoDeletion
    suspend fun undo(token: UndoDeletion)
    suspend fun archiveCategory(id: String)
    suspend fun archiveAccount(id: String)
}
