package io.qingledger.domain.repository

import io.qingledger.domain.model.CaptureMethod
import io.qingledger.domain.model.ThemeMode

data class BackupPreferences(val theme: ThemeMode, val method: CaptureMethod)
data class BackupInfo(val createdAt: Long, val transactionCount: Int, val accountCount: Int, val categoryCount: Int)

interface BackupRepository {
    suspend fun create(): ByteArray
    suspend fun inspect(bytes: ByteArray): BackupInfo
    suspend fun restore(bytes: ByteArray)
    suspend fun recovery(): ByteArray?
    fun hasRecovery(): Boolean

    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
