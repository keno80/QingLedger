package io.qingledger.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {
    @Query("SELECT COUNT(DISTINCT transactionId) FROM notification_candidates WHERE state = 'AUTO_POSTED' AND receivedAt >= :start AND receivedAt < :end")
    fun postedCount(start: Long, end: Long): Flow<Int>
    @Query("SELECT c.*, t.merchant AS merchantName, a.name AS accountName FROM notification_candidates c LEFT JOIN transactions t ON t.id = c.transactionId LEFT JOIN accounts a ON a.id = t.accountId ORDER BY c.receivedAt DESC, c.id DESC LIMIT 1")
    fun latest(): Flow<LatestCaptureRow?>
    @Query("SELECT * FROM capture_configuration WHERE id = 0") fun observeConfiguration(): Flow<CaptureConfiguration?>
    @Query("SELECT * FROM capture_sources") fun observeSources(): Flow<List<CaptureSource>>
    @Query("SELECT * FROM capture_configuration WHERE id = 0") suspend fun configuration(): CaptureConfiguration?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seed(configuration: CaptureConfiguration)
    @Query("UPDATE capture_configuration SET enabled = :enabled, generation = generation + 1 WHERE id = 0") suspend fun setEnabled(enabled: Boolean)
    @Query("UPDATE capture_configuration SET generation = generation + 1 WHERE id = 0") suspend fun nextGeneration()
    @Upsert suspend fun setSource(source: CaptureSource)
    @Query("SELECT * FROM capture_sources WHERE sourcePackage = :source") suspend fun source(source: String): CaptureSource?
    @Query("SELECT c.*, t.merchant AS merchantName, a.name AS accountName FROM notification_candidates c LEFT JOIN transactions t ON t.id = c.transactionId LEFT JOIN accounts a ON a.id = t.accountId WHERE c.hidden = 0 ORDER BY CASE WHEN c.state = 'PENDING' THEN 0 ELSE 1 END, c.receivedAt DESC LIMIT 200") fun candidates(): Flow<List<LatestCaptureRow>>
    @Query("SELECT COUNT(*) FROM notification_candidates WHERE state = 'PENDING'") fun pendingCount(): Flow<Int>
    @Query("SELECT * FROM notification_candidates WHERE id = :id") suspend fun get(id: String): NotificationCandidateEntity?
    @Query("SELECT * FROM notification_candidates WHERE sessionKey = :session AND digest = :digest") suspend fun replay(session: String, digest: String): NotificationCandidateEntity?
    @Query("SELECT * FROM notification_candidates WHERE sessionKey = :session ORDER BY receivedAt DESC LIMIT 1") suspend fun previous(session: String): NotificationCandidateEntity?
    @Query("SELECT transactionId FROM notification_candidates WHERE sessionKey = :session AND transactionId IS NOT NULL ORDER BY receivedAt DESC LIMIT 1") suspend fun sessionTransaction(session: String): String?
    @Query("SELECT id FROM transactions WHERE deletedAt IS NULL AND bookId = 'default' AND currency = 'CNY' AND amountMinor = :amount AND kind = :kind AND occurredAt >= :start AND occurredAt <= :end ORDER BY occurredAt DESC LIMIT 1") suspend fun similar(amount: Long, kind: String, start: Long, end: Long): String?
    @Insert suspend fun insert(candidate: NotificationCandidateEntity)
    @Query("UPDATE notification_candidates SET state = :state, transactionId = :transaction WHERE id = :id") suspend fun resolve(id: String, state: String, transaction: String?)
    @Query("UPDATE notification_candidates SET hidden = 1 WHERE state != 'PENDING'") suspend fun hideResolved()
    @Query("UPDATE transactions SET sourcePackage = :source WHERE id = :id") suspend fun markSource(id: String, source: String)
}

data class LatestCaptureRow(@Embedded val candidate: NotificationCandidateEntity, val merchantName: String?, val accountName: String?)
