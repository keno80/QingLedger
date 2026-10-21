package io.qingledger.app.automationui

import androidx.lifecycle.*
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.CaptureRepository
import io.qingledger.domain.repository.LedgerRepository
import java.time.LocalDate
import java.time.ZoneId
import io.qingledger.automation.notification.NotificationCaptureRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureViewModel(private val repository: CaptureRepository, private val runtime: NotificationCaptureRuntime, private val ledger: LedgerRepository) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val overview = refresh.flatMapLatest {
        flow {
            ledger.initialize()
            val zone = ZoneId.of(ledger.bookZoneId())
            val today = LocalDate.now(zone)
            val period = PeriodRange(today.atStartOfDay(zone).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            emitAll(repository.overview(period).map { it.copy(zoneId = zone.id) })
        }.catch { error -> if (error is CancellationException) throw error; emit(CaptureOverview()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureOverview())
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, CaptureSettings())
    val candidates = repository.candidates.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingCount = repository.pendingCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val method = runtime.method
    val connected = combine(runtime.method, runtime.accessibilityConnected, runtime.connected) { method, accessibility, notification ->
        when (method) { CaptureMethod.ACCESSIBILITY -> accessibility; CaptureMethod.NOTIFICATION -> notification; else -> false }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val runtimeError = runtime.error
    val lastEvent = runtime.lastEvent
    fun notificationsEnabled() = runtime.notificationsEnabled()
    fun promotionAllowed() = runtime.promotionAllowed()
    private val accessState = MutableStateFlow(false)
    val access = accessState.asStateFlow()
    private val busyState = MutableStateFlow(false)
    val busy = busyState.asStateFlow()
    private val messageState = MutableStateFlow<String?>(null)
    val message = messageState.asStateFlow()
    fun refreshAccess() { refresh.update { it + 1 }; accessState.value = when (method.value) {
        CaptureMethod.ACCESSIBILITY -> runtime.hasAccessibilityAccess()
        CaptureMethod.NOTIFICATION -> runtime.hasAccess()
        else -> false
    } }
    fun selectMethod(method: CaptureMethod) = action { runtime.selectMethod(method); refreshAccess() }
    fun reconnect() { refreshAccess(); runtime.requestReconnect() }
    fun afterRestore() { runtime.afterRestore(); clearMessage(); refreshAccess() }
    fun clearMessage() { messageState.value = null; runtime.clearError() }
    fun enable(enabled: Boolean) = action {
        refreshAccess()
        require(!enabled || method.value in setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.NOTIFICATION)) { "该采集方式尚未接入。" }
        require(!enabled || accessState.value) { "请先在系统设置中授权${method.value.label}访问。" }
        repository.setEnabled(enabled)
    }
    fun source(source: String, enabled: Boolean) = action { repository.setSource(source, enabled) }
    fun ignore(id: String, onDone: () -> Unit = {}) = action { repository.ignore(id); messageState.value = "事件已忽略。"; onDone() }
    fun clearResolved() = action { repository.clearResolved(); messageState.value = "已隐藏已处理事件，重复抑制标记仍保留。" }
    fun confirm(id: String, draft: TransactionDraft, onSaved: () -> Unit) = action {
        repository.confirm(id, draft)
        messageState.value = "已确认入账。"
        onSaved()
    }
    fun merge(candidate: PendingCandidate, onDone: () -> Unit = {}) = action {
        repository.merge(candidate.id, candidate.relatedTransactionId ?: throw IllegalArgumentException("没有可关联账单。"))
        messageState.value = "已关联原账单，未重复入账。"
        onDone()
    }
    private fun action(block: suspend () -> Unit) {
        if (busyState.value) return
        busyState.value = true
        viewModelScope.launch {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                messageState.value = if (error is IllegalArgumentException) error.message else "操作失败，请重试。"
            } finally { busyState.value = false }
        }
    }
    class Factory(private val repository: CaptureRepository, private val runtime: NotificationCaptureRuntime, private val ledger: LedgerRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CaptureViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return CaptureViewModel(repository, runtime, ledger) as T
        }
    }
}
