package io.qingledger.domain.repository

import io.qingledger.domain.model.*
import kotlinx.coroutines.flow.Flow

interface CaptureRepository {
    val settings: Flow<CaptureSettings>
    val candidates: Flow<List<PendingCandidate>>
    val pendingCount: Flow<Int>
    fun overview(period: PeriodRange): Flow<CaptureOverview>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setSource(source: String, enabled: Boolean)
    suspend fun capture(event: CapturedNotification, proposal: NotificationProposal): Boolean
    suspend fun capturedState(event: CapturedNotification): String?
    suspend fun accessibilityReceipts(profile: String): List<StoredAccessibilityReceipt> = emptyList()
    suspend fun confirm(id: String, draft: TransactionDraft): String
    suspend fun ignore(id: String)
    suspend fun merge(id: String, transactionId: String)
    suspend fun clearResolved()
}
