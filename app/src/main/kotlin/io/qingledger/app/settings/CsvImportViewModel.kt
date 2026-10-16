package io.qingledger.app.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.qingledger.domain.importing.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CsvImportState(val busy: Boolean = false, val plan: CsvImportPlan? = null,
    val result: CsvImportResult? = null, val error: String? = null)

class CsvImportViewModel(private val repository: CsvImportRepository, private val resolver: ContentResolver) : ViewModel() {
    private val mutableState = MutableStateFlow(CsvImportState())
    val state = mutableState.asStateFlow()
    fun reset() { if (!state.value.busy) mutableState.value = CsvImportState() }
    fun load(uri: Uri) = action {
        mutableState.update { it.copy(plan = null, result = null) }
        val bytes = (resolver.openInputStream(uri) ?: throw IOException()).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                require(output.size() + size <= CsvImportRepository.MAX_BYTES) { "CSV 文件超过 16MB。" }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("请选择 UTF-8 编码的轻账 CSV 文件。") }
        val plan = repository.preview(CsvImportParser.parse(text))
        mutableState.update { it.copy(plan = plan) }
    }
    fun choose(key: String, choice: CsvMappingChoice) = action {
        val plan = state.value.plan ?: return@action
        val choices = plan.mappings.associate { it.key to it.choice } + (key to choice)
        val updated = repository.preview(plan.file, choices)
        mutableState.update { it.copy(plan = updated) }
    }
    fun refresh() = action {
        val plan = state.value.plan ?: return@action
        val updated = repository.preview(plan.file, plan.mappings.associate { it.key to it.choice })
        mutableState.update { it.copy(plan = updated) }
    }
    fun commit() = action {
        val plan = state.value.plan ?: return@action
        if (!plan.canImport) return@action
        val result = repository.commit(plan)
        mutableState.update { it.copy(plan = null, result = result) }
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = when (error) {
                    is IOException, is SecurityException -> "读取文件失败，请重新选择文件。"
                    is IllegalArgumentException, is IllegalStateException -> error.message ?: "无法导入此文件。"
                    else -> "导入失败，请重试。"
                }) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    class Factory(private val repository: CsvImportRepository, private val resolver: ContentResolver) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CsvImportViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return CsvImportViewModel(repository, resolver) as T
        }
    }
}
