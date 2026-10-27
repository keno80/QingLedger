package io.qingledger.domain.repository

import io.qingledger.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface CaptureRepository {
    val settings: Flow<CaptureSettings>
    val candidates: Flow<List<PendingCandidate>>
    val pendingCount: Flow<Int>
    fun candidate(id: String): Flow<PendingCandidate?> = candidates.map { rows -> rows.firstOrNull { it.id == id } }
    fun overview(period: PeriodRange): Flow<CaptureOverview>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setSource(source: String, enabled: Boolean)
    suspend fun setBookingMode(mode: CaptureBookingMode) { throw UnsupportedOperationException("入账方式配置不可用。") }
    suspend fun setDailySummaryEnabled(enabled: Boolean) { throw UnsupportedOperationException("今日收支通知配置不可用。") }
    suspend fun capture(event: CapturedNotification, proposal: NotificationProposal): Boolean
    suspend fun capturedState(event: CapturedNotification): String?
    suspend fun capturedCandidateId(event: CapturedNotification): String? = null
    suspend fun accessibilityReceipts(profile: String): List<StoredAccessibilityReceipt> = emptyList()
    suspend fun confirm(id: String, draft: TransactionDraft): String
    suspend fun confirmWithAccount(id: String, draft: TransactionDraft, recognizedAccountName: String?): String = confirm(id, draft)
    suspend fun ignore(id: String)
    suspend fun merge(id: String, transactionId: String)
    suspend fun clearResolved()
}
