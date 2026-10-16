package io.qingledger.app.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.qingledger.domain.repository.BackupInfo
import io.qingledger.domain.repository.BackupRepository
import io.qingledger.domain.export.CsvExportRepository
import io.qingledger.domain.export.CsvExportRequest
import io.qingledger.domain.export.LedgerCsv
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DataManagementState(val busy: Boolean = false, val message: String? = null, val preview: BackupInfo? = null, val recoveryAvailable: Boolean = false, val restored: Int = 0)

class DataManagementViewModel(private val backup: BackupRepository, private val resolver: ContentResolver, private val csvExport: CsvExportRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DataManagementState(recoveryAvailable = backup.hasRecovery()))
    val state = mutableState.asStateFlow()
    private var selected: ByteArray? = null

    fun exportCsv(uri: Uri, request: CsvExportRequest) = action {
        val rows = csvExport.snapshot(request)
        val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Unable to open document")
        output.bufferedWriter(Charsets.UTF_8).use { LedgerCsv.write(it, rows) }
        mutableState.update { it.copy(message = "CSV 已导出，共 ${rows.size} 笔账单。") }
    }

    fun create(uri: Uri, recovery: Boolean) = action {
        val bytes = if (recovery) backup.recovery() ?: throw IllegalArgumentException("暂无恢复前备份。") else backup.create()
        val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Unable to open document")
        output.use { it.write(bytes); it.flush() }
        mutableState.update { it.copy(message = if (recovery) "恢复前备份已导出。" else "本地备份已保存。") }
    }
    fun inspect(uri: Uri) = action {
        selected = null
        mutableState.update { it.copy(preview = null) }
        val input = resolver.openInputStream(uri) ?: throw IOException("Unable to read document")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= BackupRepository.MAX_BYTES) { "备份文件超过 16MB。" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val info = backup.inspect(bytes)
        selected = bytes
        mutableState.update { it.copy(preview = info) }
    }
    fun cancelPreview() { if (!state.value.busy) { selected = null; mutableState.update { it.copy(preview = null) } } }
    fun restore() {
        val bytes = selected ?: return
        action {
            backup.restore(bytes)
            selected = null
            mutableState.update { it.copy(preview = null, recoveryAvailable = true, restored = it.restored + 1, message = "恢复完成，自动采集已暂停。恢复前数据已自动备份，可从恢复入口导出。") }
        }
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = when (error) {
                    is IOException, is SecurityException -> "文件操作失败，请重新选择位置或文件。未完成的文件请重新导出。"
                    is IllegalArgumentException, is IllegalStateException -> error.message ?: "备份操作失败。"
                    else -> "操作失败，请重试。"
                }, recoveryAvailable = backup.hasRecovery()) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    class Factory(private val backup: BackupRepository, private val resolver: ContentResolver, private val csvExport: CsvExportRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DataManagementViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return DataManagementViewModel(backup, resolver, csvExport) as T
        }
    }
}
