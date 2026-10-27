package io.qingledger.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {
    @Query("SELECT DISTINCT c.sourcePackage AS source, c.kind AS kind, c.amountMinor AS amountMinor, COALESCE(c.merchant, t.merchant) AS merchant, c.sessionKey AS session FROM notification_candidates c LEFT JOIN transactions t ON t.id = c.transactionId WHERE c.profileKey = :profile AND c.sessionKey LIKE 'a11y:%' AND c.kind IN ('EXPENSE','INCOME','REFUND') AND c.amountMinor IS NOT NULL AND c.amountMinor > 0 AND c.state IN ('AUTO_POSTED','MERGED','CONFIRMED','PENDING','IGNORED') ORDER BY (c.state IN ('PENDING','IGNORED')) DESC, (t.accountId IS NOT NULL) DESC, c.receivedAt DESC LIMIT 64")
    suspend fun accessibilityReceipts(profile: String): List<ReceiptHistoryRow>
    @Query("SELECT COUNT(DISTINCT c.transactionId) FROM notification_candidates c JOIN transactions t ON t.id = c.transactionId WHERE c.state IN ('AUTO_POSTED','CONFIRMED') AND t.deletedAt IS NULL AND t.occurredAt >= :start AND t.occurredAt < :end")
    fun postedCount(start: Long, end: Long): Flow<Int>
    @Query("SELECT c.*, COALESCE(t.merchant, c.merchant) AS merchantName, COALESCE(a.name, c.accountName) AS matchedAccountName FROM notification_candidates c LEFT JOIN transactions t ON t.id = c.transactionId LEFT JOIN accounts a ON a.id = t.accountId ORDER BY c.receivedAt DESC, c.id DESC LIMIT 1")
    fun latest(): Flow<LatestCaptureRow?>
    @Query("SELECT * FROM capture_configuration WHERE id = 0") fun observeConfiguration(): Flow<CaptureConfiguration?>
    @Query("SELECT * FROM capture_sources") fun observeSources(): Flow<List<CaptureSource>>
    @Query("SELECT * FROM capture_configuration WHERE id = 0") suspend fun configuration(): CaptureConfiguration?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seed(configuration: CaptureConfiguration)
    @Query("UPDATE capture_configuration SET enabled = :enabled, generation = generation + 1 WHERE id = 0") suspend fun setEnabled(enabled: Boolean)
    @Query("UPDATE capture_configuration SET generation = generation + 1 WHERE id = 0") suspend fun nextGeneration()
    @Query("UPDATE capture_configuration SET bookingMode = :mode, generation = generation + 1 WHERE id = 0 AND bookingMode != :mode") suspend fun setBookingMode(mode: String)
    @Query("UPDATE capture_configuration SET dailySummaryEnabled = :enabled WHERE id = 0") suspend fun setDailySummaryEnabled(enabled: Boolean)
    @Upsert suspend fun setSource(source: CaptureSource)
    @Query("SELECT * FROM capture_sources WHERE sourcePackage = :source") suspend fun source(source: String): CaptureSource?
    @Query("SELECT c.*, COALESCE(t.merchant, c.merchant) AS merchantName, COALESCE(a.name, c.accountName) AS matchedAccountName FROM notification_candidates c LEFT JOIN transactions t ON t.id = c.transactionId LEFT JOIN accounts a ON a.id = t.accountId WHERE c.hidden = 0 ORDER BY CASE WHEN c.state = 'PENDING' THEN 0 ELSE 1 END, c.receivedAt DESC LIMIT 200") fun candidates(): Flow<List<LatestCaptureRow>>
    @Query("SELECT c.*, COALESCE(t.merchant, c.merchant) AS merchantName, COALESCE(a.name, c.accountName) AS matchedAccountName FROM notification_candidates c LEFT JOIN transactions t ON t.id = c.transactionId LEFT JOIN accounts a ON a.id = t.accountId WHERE c.id = :id") fun observeCandidate(id: String): Flow<LatestCaptureRow?>
    @Query("SELECT COUNT(*) FROM notification_candidates WHERE state = 'PENDING'") fun pendingCount(): Flow<Int>
    @Query("SELECT * FROM notification_candidates WHERE id = :id") suspend fun get(id: String): NotificationCandidateEntity?
    @Query("SELECT * FROM notification_candidates WHERE sessionKey = :session AND digest = :digest") suspend fun replay(session: String, digest: String): NotificationCandidateEntity?
    @Query("SELECT * FROM notification_candidates WHERE sessionKey = :session ORDER BY receivedAt DESC LIMIT 1") suspend fun previous(session: String): NotificationCandidateEntity?
    @Query("SELECT * FROM notification_candidates WHERE sessionKey = :session AND state = 'PENDING' ORDER BY receivedAt DESC LIMIT 1") suspend fun pending(session: String): NotificationCandidateEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM notification_candidates WHERE sessionKey = :session AND state = 'IGNORED')") suspend fun isIgnored(session: String): Boolean
    @Query("SELECT transactionId FROM notification_candidates WHERE sessionKey = :session AND state = 'CONFIRMED' AND transactionId IS NOT NULL ORDER BY receivedAt DESC LIMIT 1") suspend fun confirmedTransaction(session: String): String?
    @Query("SELECT transactionId FROM notification_candidates WHERE sessionKey = :session AND transactionId IS NOT NULL ORDER BY receivedAt DESC LIMIT 1") suspend fun sessionTransaction(session: String): String?
    @Query("SELECT transactionId FROM notification_candidates WHERE sessionKey = :session AND kind = :kind AND transactionId IS NOT NULL ORDER BY receivedAt DESC LIMIT 1") suspend fun sessionTransactionByKind(session: String, kind: String?): String?
    @Query("SELECT id FROM transactions WHERE deletedAt IS NULL AND bookId = 'default' AND currency = 'CNY' AND amountMinor = :amount AND kind = :kind AND occurredAt >= :start AND occurredAt <= :end ORDER BY occurredAt DESC LIMIT 1") suspend fun similar(amount: Long, kind: String, start: Long, end: Long): String?
    @Insert suspend fun insert(candidate: NotificationCandidateEntity)
    @Update suspend fun update(candidate: NotificationCandidateEntity)
    @Query("UPDATE notification_candidates SET state = :state, transactionId = :transaction WHERE id = :id") suspend fun resolve(id: String, state: String, transaction: String?)
    @Query("UPDATE notification_candidates SET state = :state, transactionId = :transaction WHERE sessionKey = :session AND state = 'PENDING'") suspend fun resolvePendingSession(session: String, state: String, transaction: String?)
    @Query("UPDATE notification_candidates SET hidden = 1 WHERE state != 'PENDING'") suspend fun hideResolved()
    @Query("UPDATE transactions SET sourcePackage = :source WHERE id = :id") suspend fun markSource(id: String, source: String)
}

data class LatestCaptureRow(@Embedded val candidate: NotificationCandidateEntity, val merchantName: String?, val matchedAccountName: String?)
data class ReceiptHistoryRow(val source: String, val kind: String, val amountMinor: Long, val merchant: String?, val session: String)
