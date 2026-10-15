package io.qingledger.automation.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.service.notification.StatusBarNotification
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.CaptureRepository
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CaptureHost { val captureRuntime: NotificationCaptureRuntime }

class NotificationCaptureRuntime(
    context: Context, private val repository: CaptureRepository,
    private val accessibilitySources: Set<String> = setOf("com.eg.android.AlipayGphone", "com.tencent.mm"),
) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("capture_method", Context.MODE_PRIVATE)
    private val mutableMethod = MutableStateFlow(runCatching { CaptureMethod.valueOf(preferences.getString("method", null) ?: "ACCESSIBILITY") }.getOrDefault(CaptureMethod.ACCESSIBILITY))
    val method = mutableMethod.asStateFlow()
    private val methodMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Queued(val event: CapturedNotification, val text: String, val method: CaptureMethod, val accountName: String? = null)
    private val queue = Channel<Queued>(128)
    private val sessions = ConcurrentHashMap<String, String>()
    val settings = repository.settings.stateIn(scope, SharingStarted.Eagerly, CaptureSettings())
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private val mutableAccessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected = mutableAccessibilityConnected.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableLastEvent = MutableStateFlow<Long?>(null)
    val lastEvent = mutableLastEvent.asStateFlow()
    private val liveNotification = CaptureLiveNotification(app)
    private val noticeGate = CaptureNoticeGate()
    fun notificationsEnabled() = liveNotification.notificationsEnabled()
    fun promotionAllowed() = liveNotification.promotionAllowed()

    init {
        scope.launch {
            methodMutex.withLock { if (!preferences.contains("method")) {
                try {
                    repository.setEnabled(false)
                    check(preferences.edit().putString("method", CaptureMethod.ACCESSIBILITY.name).commit())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableError.value = "新采集方式初始化失败，请检查暂停状态后重试。"
                }
            } }
        }
        scope.launch {
            for ((event, text, origin, accountName) in queue) {
                var announced = false
                try {
                    if (!isCurrent(event, origin)) continue
                    val parsed = (if (origin == CaptureMethod.ACCESSIBILITY) AccessibilityRules.propose(text) else NotificationRules.propose(text)) ?: continue
                    if (repository.capturedState(event) != null) continue
                    if (noticeGate.accept("${event.generation}|${event.sessionKey}|${event.digest}")) {
                        liveNotification.started()
                        announced = true
                    }
                    val proposal = parsed.copy(accountName = parsed.accountName ?: accountName)
                    val version = try {
                        @Suppress("DEPRECATION")
                        app.packageManager.getPackageInfo(event.sourcePackage, 0).longVersionCode
                    } catch (_: Exception) { null }
                    if (repository.capture(event.copy(versionCode = version), proposal)) {
                        mutableLastEvent.value = event.receivedAt
                        if (isCurrent(event, origin)) liveNotification.finished(repository.capturedState(event)) else liveNotification.cancel()
                    } else liveNotification.cancel()
                    announced = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableError.value = "事件保存失败，可能存在未采集记录。"
                    if (isCurrent(event, origin)) { liveNotification.finished(null); announced = false }
                } finally {
                    if (announced) liveNotification.cancel()
                }
            }
        }
    }
    private fun isCurrent(event: CapturedNotification, origin: CaptureMethod): Boolean =
        origin == method.value && settings.value.enabled && event.generation == settings.value.generation && event.sourcePackage in settings.value.sources
    fun hasAccess(): Boolean = app.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(app, LedgerNotificationListener::class.java))
    fun hasAccessibilityAccess(): Boolean {
        val component = ComponentName(app, io.qingledger.automation.accessibility.LedgerAccessibilityService::class.java)
        return app.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == component }
    }
    suspend fun selectMethod(selected: CaptureMethod) {
        methodMutex.withLock {
        if (selected == method.value) return
        repository.setEnabled(false)
        check(preferences.edit().putString("method", selected.name).commit()) { "采集方式保存失败。" }
        mutableMethod.value = selected
        sessions.clear()
        }
    }
    fun accessibilityConnectionChanged(connected: Boolean) {
        mutableAccessibilityConnected.value = connected
        if (!connected && method.value == CaptureMethod.ACCESSIBILITY) pauseAfterDisconnect("无障碍")
    }
    fun reportAccessibilityError() { mutableError.value = "无障碍页面读取失败，可能存在未采集记录。" }
    fun acceptsAccessibility(source: String): Boolean = method.value == CaptureMethod.ACCESSIBILITY &&
        settings.value.enabled && source in accessibilitySources && source in settings.value.sources
    fun accessibilityPosted(source: String, session: String, text: String, generation: Long, accountName: String? = null) {
        if (!acceptsAccessibility(source)) return
        val event = CapturedNotification(source, Process.myUserHandle().toString(), "a11y:" + hash(session), hash(text + "|account:" + accountName.orEmpty()), System.currentTimeMillis(), null, generation)
        if (queue.trySend(Queued(event, text.take(8192), CaptureMethod.ACCESSIBILITY, accountName)).isFailure) mutableError.value = "事件队列已满，可能存在未采集记录。"
    }
    private fun pauseAfterDisconnect(label: String) {
        if (!settings.value.enabled) return
        mutableError.value = "${label}监听已中断，采集已暂停；中断期间可能漏记。"
        scope.launch {
            try { repository.setEnabled(false) }
            catch (error: Exception) { if (error is CancellationException) throw error; mutableError.value = "监听已中断，暂停状态保存失败，请重新打开应用检查。" }
        }
    }
    fun connectionChanged(connected: Boolean) {
        mutableConnected.value = connected
        if (!connected && method.value == CaptureMethod.NOTIFICATION) pauseAfterDisconnect("通知")
    }
    fun requestReconnect() {
        if (hasAccess()) android.service.notification.NotificationListenerService.requestRebind(ComponentName(app, LedgerNotificationListener::class.java))
    }
    fun clearError() { mutableError.value = null }
    fun removed(sbn: StatusBarNotification) { sessions.remove(sbn.key) }
    fun posted(sbn: StatusBarNotification) {
        val current = settings.value
        if (method.value != CaptureMethod.NOTIFICATION || !current.enabled || sbn.packageName !in current.sources || sbn.user != Process.myUserHandle()) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = sbn.notification.extras
        val fields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT).mapNotNull { extras.getCharSequence(it)?.toString()?.take(4096) }.distinct()
        val text = fields.joinToString("\n").take(8192)
        if (text.isBlank()) return
        val profile = sbn.user.toString()
        val session = sessions.getOrPut(sbn.key) { hash("${sbn.packageName}|$profile|${sbn.key}|${sbn.postTime}") }
        val event = CapturedNotification(sbn.packageName, profile, session, hash(text), System.currentTimeMillis(), null, current.generation)
        if (queue.trySend(Queued(event, text, CaptureMethod.NOTIFICATION)).isFailure) mutableError.value = "事件队列已满，可能存在未采集记录。"
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
