package io.qingledger.app.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.*
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LedgerSelection(
    val month: YearMonth = YearMonth.now(), val query: String = "",
    val kind: TransactionKind? = null, val categoryId: String? = null,
    val accountId: String? = null, val limit: Int = 50,
)
data class LedgerUiState(
    val loading: Boolean = true, val selection: LedgerSelection = LedgerSelection(),
    val rows: List<LedgerTransaction> = emptyList(), val summary: LedgerSummary = LedgerSummary(),
    val categories: List<Category> = emptyList(), val accounts: List<Account> = emptyList(),
    val zoneId: String = ZoneId.systemDefault().id,
)
data class AssetsUiState(val loading: Boolean = true, val rows: List<AccountBalance> = emptyList(), val summary: AssetSummary = AssetSummary(), val error: String? = null)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val selection = MutableStateFlow(LedgerSelection())
    private val mutableState = MutableStateFlow(LedgerUiState())
    val state: StateFlow<LedgerUiState> = mutableState.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableOverview = MutableStateFlow<LedgerOverview?>(null)
    val overview = mutableOverview.asStateFlow()
    private val mutableAssets = MutableStateFlow(AssetsUiState())
    val assets = mutableAssets.asStateFlow()
    private val mutableUndo = MutableStateFlow<UndoDeletion?>(null)
    val undo = mutableUndo.asStateFlow()
    private var undoTimer: Job? = null

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                val zoneId = repository.bookZoneId()
                launch {
                    repository.accountBalances().map { AssetsUiState(false, it, AssetRules.summarize(it)) }
                        .catch { emit(AssetsUiState(false, error = "资产读取失败，请重新打开应用。")) }
                        .collect { mutableAssets.value = it }
                }
                launch { repository.overview(zoneId).catch { mutableOverview.value = null }.collect { mutableOverview.value = it } }
                selection.value = selection.value.copy(month = YearMonth.now(ZoneId.of(zoneId)))
                selection.flatMapLatest { current ->
                    val period = LedgerRules.period(current.month, ZoneId.of(zoneId))
                    combine(repository.transactions(LedgerFilter(period, current.query, current.kind, current.categoryId, current.accountId, current.limit)), repository.summary(period), repository.categories, repository.accounts) { rows, summary, categories, accounts ->
                        LedgerUiState(false, current, rows, summary, categories, accounts, zoneId)
                    }
                }.collect { mutableState.value = it }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableMessage.value = "账本读取失败，请重新打开应用。"
            }
        }
    }

    fun changeMonth(offset: Long) { selection.update { it.copy(month = it.month.plusMonths(offset), limit = 50) } }
    fun search(value: String) { selection.update { it.copy(query = value, limit = 50) } }
    fun resetSearch(month: YearMonth) { selection.value = LedgerSelection(month = month) }
    fun filter(kind: TransactionKind?) { selection.update { it.copy(kind = kind, categoryId = null, limit = 50) } }
    fun filterCategory(id: String?) { selection.update { it.copy(categoryId = id, limit = 50) } }
    fun filterAccount(id: String?) { selection.update { it.copy(accountId = id, limit = 50) } }
    fun loadMore() { selection.update { it.copy(limit = (it.limit + 50).coerceAtMost(10000)) } }
    fun clearMessage() { mutableMessage.value = null }
    suspend fun get(id: String) = repository.get(id)
    fun changes(id: String) = repository.changes(id)
    fun saveCategory(name: String, kind: TransactionKind, id: String?, onSaved: () -> Unit) = action {
        repository.saveCategory(name, kind, id)
        mutableMessage.value = "分类已保存。"
        onSaved()
    }
    fun saveAccount(name: String, id: String?, onSaved: () -> Unit) = action {
        repository.saveAccount(name, id)
        mutableMessage.value = "账户已保存。"
        onSaved()
    }
    fun saveAccountDetails(name: String, openingBalanceMinor: Long?, id: String?, onSaved: () -> Unit) = action {
        repository.saveAccountDetails(name, openingBalanceMinor, id)
        mutableMessage.value = "账户已保存。"
        onSaved()
    }
    fun archiveReference(id: String, category: Boolean) = action {
        if (category) repository.archiveCategory(id) else repository.archiveAccount(id)
        mutableMessage.value = "已归档，已有账单仍保留关联。"
    }
    fun restoreReference(id: String, category: Boolean) = action {
        if (category) repository.restoreCategory(id) else repository.restoreAccount(id)
        mutableMessage.value = "已恢复，可用于新账单。"
    }

    fun save(draft: TransactionDraft, previous: LedgerTransaction?, onSaved: () -> Unit) = action {
        repository.save(draft, previous?.id, previous?.revision)
        selection.update { it.copy(month = YearMonth.from(java.time.Instant.ofEpochMilli(draft.occurredAt).atZone(ZoneId.of(state.value.zoneId))), query = "", kind = null, categoryId = null, accountId = null, limit = 50) }
        mutableMessage.value = "账单已保存。"
        onSaved()
    }
    fun delete(row: LedgerTransaction, onDeleted: () -> Unit) = action {
        val token = repository.delete(row.id, row.revision)
        mutableUndo.value = token
        mutableMessage.value = "账单已删除，可在 10 秒内撤销。"
        undoTimer?.cancel()
        undoTimer = viewModelScope.launch { delay(10000); if (mutableUndo.value == token) mutableUndo.value = null }
        onDeleted()
    }
    fun undoDelete() = action {
        val token = mutableUndo.value ?: return@action
        repository.undo(token)
        mutableUndo.value = null
        mutableMessage.value = "已恢复账单。"
    }
    private fun action(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableMessage.value = when (error) {
                    is LedgerConflict, is IllegalArgumentException -> error.message
                    else -> "操作失败，账单未保存。请重试。"
                }
            } finally { mutableBusy.value = false }
        }
    }
    class Factory(private val repository: LedgerRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LedgerViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return LedgerViewModel(repository) as T
        }
    }
}
