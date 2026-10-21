package io.qingledger.app.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.LedgerRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StatisticsUiState(
    val loading: Boolean = true,
    val selection: StatisticsSelection = StatisticsSelection(),
    val overview: StatisticsOverview = StatisticsOverview(),
    val rows: List<LedgerTransaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val zoneId: String = ZoneId.systemDefault().id,
    val error: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatisticsViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val selection = MutableStateFlow(StatisticsSelection())
    private val mutableState = MutableStateFlow(StatisticsUiState())
    val state = mutableState.asStateFlow()
    private var observer: Job? = null
    init { reload() }
    fun reload() {
        observer?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, rows = emptyList(), overview = StatisticsOverview(), error = null)
        observer = viewModelScope.launch {
            try {
                repository.initialize()
                val zone = ZoneId.of(repository.bookZoneId())
                selection.value = StatisticsSelection(anchor = LocalDate.now(zone))
                selection.flatMapLatest { current ->
                    mutableState.value = mutableState.value.copy(selection = current, loading = true, rows = emptyList(), overview = StatisticsOverview(), error = null)
                    combine(repository.statisticsTransactions(current.range(zone)), repository.categories) { rows, categories ->
                        StatisticsUiState(false, current, StatisticsOverview.calculate(rows, current, zone), rows, categories, zone.id)
                    }.flowOn(Dispatchers.Default)
                }.collect { mutableState.value = it }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(loading = false, error = "统计读取失败，请重试。")
            }
        }
    }
    fun period(value: StatisticsPeriod) { selection.update { it.copy(period = value) } }
    fun move(offset: Long) { selection.update { it.move(offset) } }
    class Factory(private val repository: LedgerRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = StatisticsViewModel(repository) as T
    }
}
