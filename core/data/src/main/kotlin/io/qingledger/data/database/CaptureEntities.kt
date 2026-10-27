package io.qingledger.data.database

import androidx.room.*

@Entity(tableName = "capture_configuration")
data class CaptureConfiguration(
    @PrimaryKey val id: Int = 0, val enabled: Boolean = false, val generation: Long = 0,
    @ColumnInfo(defaultValue = "'DIRECT'") val bookingMode: String = "DIRECT",
    @ColumnInfo(defaultValue = "1") val dailySummaryEnabled: Boolean = true,
)
@Entity(tableName = "capture_sources")
data class CaptureSource(@PrimaryKey val sourcePackage: String, val enabled: Boolean)
@Entity(tableName = "notification_candidates", indices = [Index(value = ["sessionKey", "digest"], unique = true), Index(value = ["state", "receivedAt"])])
data class NotificationCandidateEntity(
    @PrimaryKey val id: String, val sourcePackage: String, val profileKey: String,
    val sessionKey: String, val digest: String, val versionCode: Long?, val receivedAt: Long,
    val amountMinor: Long?, val kind: String?, val reason: String,
    val state: String = "PENDING", val transactionId: String? = null,
    val relatedTransactionId: String? = null, val hidden: Boolean = false,
    val merchant: String? = null, val accountName: String? = null,
    val originalAmountMinor: Long? = null, val discountMinor: Long? = null,
)
