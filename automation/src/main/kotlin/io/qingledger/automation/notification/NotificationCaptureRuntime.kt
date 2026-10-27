package io.qingledger.automation.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.os.SystemClock
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
    private data class Queued(val event: CapturedNotification, val text: String, val method: CaptureMethod, val accountName: String? = null, val merchantName: String? = null, val originalAmountMinor: Long? = null, val discountMinor: Long? = null)
    private val queue = Channel<Queued>(128)
    private val sessions = ConcurrentHashMap<String, String>()
    private val paymentCache = PaymentSessionCache()
    private val mutableDiagnostics = MutableStateFlow<List<CaptureDiagnostic>>(emptyList())
    val diagnostics = mutableDiagnostics.asStateFlow()
    @Synchronized internal fun diagnostic(source: String, stage: String, reason: String, fields: Int? = null, details: String? = null) {
        if (source !in accessibilitySources) return
        val now = System.currentTimeMillis()
        val item = CaptureDiagnostic(source, now, stage, reason, fields, details)
        val previous = mutableDiagnostics.value
        if (previous.any { it.sourcePackage == source && it.stage == stage && it.reason == reason && it.fields == fields && it.details == details && now - it.at in 0..2000 }) return
        mutableDiagnostics.value = (previous + item).takeLast(40)
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.i("QingLedgerCapture", "$source $stage $reason fields=$fields ${details.orEmpty()}")
    }
    fun accessibilityEventReceived(source: String, notification: Boolean) {
        diagnostic(source, "EVENT", when {
            method.value != CaptureMethod.ACCESSIBILITY -> "METHOD_INACTIVE"
            !settings.value.enabled -> "CAPTURE_DISABLED"
            source !in settings.value.sources -> "SOURCE_DISABLED"
            notification -> "NOTIFICATION_EVENT"
            else -> "WINDOW_EVENT"
        })
    }
    val settings = repository.settings.stateIn(scope, SharingStarted.Eagerly, CaptureSettings())
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    private val mutableAccessibilityConnected = MutableStateFlow(false)
    val accessibilityConnected = mutableAccessibilityConnected.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableLastEvent = MutableStateFlow<Long?>(null)
    val lastEvent = mutableLastEvent.asStateFlow()
    var confirmationHandler: ((String) -> Unit)? = null
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
            for ((event, text, origin, accountName, merchantName, originalAmountMinor, discountMinor) in queue) {
                var announced = false
                try {
                    if (!isCurrent(event, origin)) { diagnostic(event.sourcePackage, "SAVE", "SETTINGS_CHANGED"); continue }
                    val parsed = (when {
                        event.sourcePackage == ALIPAY && (origin == CaptureMethod.NOTIFICATION || event.sessionKey.startsWith("notice:")) -> AlipayNoticeRules.propose(text)
                        origin in setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.SHIZUKU) -> AccessibilityRules.propose(text)
                        else -> NotificationRules.propose(text)
                    }) ?: continue
                    if (repository.capturedState(event) != null) { diagnostic(event.sourcePackage, "SAVE", "DUPLICATE"); continue }
                    if (noticeGate.accept("${event.generation}|${event.sessionKey}|${event.digest}")) {
                        liveNotification.started()
                        announced = true
                    }
                    val proposal = parsed.copy(accountName = parsed.accountName ?: accountName, merchantName = parsed.merchantName ?: merchantName,
                        originalAmountMinor = originalAmountMinor, discountMinor = discountMinor)
                    val version = try {
                        @Suppress("DEPRECATION")
                        app.packageManager.getPackageInfo(event.sourcePackage, 0).longVersionCode
                    } catch (_: Exception) { null }
                    if (repository.capture(event.copy(versionCode = version), proposal)) {
                        mutableLastEvent.value = event.receivedAt
                        val savedState = repository.capturedState(event)
                        diagnostic(event.sourcePackage, "SAVE", savedState ?: "EVENT_REJECTED")
                        if (savedState == "PENDING") repository.capturedCandidateId(event)?.let { confirmationHandler?.invoke(it) }
                        if (isCurrent(event, origin)) liveNotification.finished(savedState) else liveNotification.cancel()
                    } else { diagnostic(event.sourcePackage, "SAVE", "EVENT_REJECTED"); liveNotification.cancel() }
                    announced = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableError.value = "事件保存失败，可能存在未采集记录。"
                    diagnostic(event.sourcePackage, "SAVE", "SAVE_FAILED")
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
        synchronized(paymentCache) { paymentCache.clear() }
        }
    }
    fun accessibilityConnectionChanged(connected: Boolean) {
        mutableAccessibilityConnected.value = connected
        if (!connected && method.value == CaptureMethod.ACCESSIBILITY) pauseAfterDisconnect("无障碍")
    }
    fun reportAccessibilityError() { mutableError.value = "无障碍页面读取失败，可能存在未采集记录。" }
    fun acceptsAccessibility(source: String): Boolean = method.value == CaptureMethod.ACCESSIBILITY &&
        settings.value.enabled && source in accessibilitySources && source in settings.value.sources
    internal suspend fun accessibilityReceipts() = repository.accessibilityReceipts(Process.myUserHandle().toString())
    fun accessibilityPosted(source: String, session: String, text: String, generation: Long, accountName: String? = null, merchantName: String? = null, originalAmountMinor: Long? = null, discountMinor: Long? = null) {
        pagePosted(CaptureMethod.ACCESSIBILITY, source, session, text, generation, accountName, merchantName, originalAmountMinor, discountMinor)
    }
    fun shizukuPosted(source: String, session: String, text: String, generation: Long, accountName: String? = null, merchantName: String? = null, originalAmountMinor: Long? = null, discountMinor: Long? = null) {
        pagePosted(CaptureMethod.SHIZUKU, source, session, text, generation, accountName, merchantName, originalAmountMinor, discountMinor)
    }
    private fun pagePosted(origin: CaptureMethod, source: String, session: String, text: String, generation: Long, accountName: String?, merchantName: String?, originalAmountMinor: Long?, discountMinor: Long?) {
        if (origin != method.value || !settings.value.enabled || source !in accessibilitySources || source !in settings.value.sources) return
        // Alipay's page and notification do not share an order ID. Only its settled
        // notification writes a bill; never merge separate payments by amount/time.
        if (source == ALIPAY) { diagnostic(source, "RULE", "ALIPAY_NOTICE_REQUIRED"); return }
        val merchant = merchantName?.trim()?.take(128)?.takeIf { it.isNotBlank() }
        val sessionKey = if (Regex("^a11y:[a-f0-9]{64}$").matches(session)) session else "a11y:" + hash(session)
        val event = CapturedNotification(source, Process.myUserHandle().toString(), sessionKey, hash(text + "|account:" + accountName.orEmpty() + "|merchant:" + merchant.orEmpty() + "|prices:$originalAmountMinor:$discountMinor"), System.currentTimeMillis(), null, generation)
        if (queue.trySend(Queued(event, text.take(8192), origin, accountName, merchant, originalAmountMinor, discountMinor)).isFailure) {
            diagnostic(source, "SAVE", "QUEUE_FULL"); mutableError.value = "事件队列已满，可能存在未采集记录。"
        } else diagnostic(source, "RULE", "QUEUED")
    }
    fun observePaymentPage(source: String, generation: Long, text: String, selected: List<String>) {
        if (source == ALIPAY && acceptsAccessibility(source)) synchronized(paymentCache) {
            paymentCache.observe(source, generation, text, selected, SystemClock.elapsedRealtime())
        }
    }
    fun accessibilityNotificationPosted(source: String, notification: Notification, eventTime: Long) {
        if (!acceptsAccessibility(source)) return
        if (source != ALIPAY) { diagnostic(source, "RULE", "NOTICE_PATH_UNSUPPORTED"); return }
        if (connected.value && hasAccess()) return
        settledNotice(source, notification, "event:$eventTime", CaptureMethod.ACCESSIBILITY)
    }
    private fun settledNotice(source: String, notification: Notification, fallbackInstance: String, origin: CaptureMethod) {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val current = settings.value
        if (!current.enabled || source !in current.sources) return
        val extras = notification.extras
        val fields = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString()?.take(4096) }.distinct()
        val text = fields.joinToString("\n").take(8192)
        val proposal = AlipayNoticeRules.propose(text) ?: run { diagnostic(source, "RULE", "NOTICE_TEMPLATE_UNSUPPORTED"); return }
        val instance = if (notification.`when` > 0) notification.`when`.toString() else fallbackInstance
        val profile = Process.myUserHandle().toString()
        val session = "notice:" + hash("$source|$profile|$instance|${notification.contentIntent?.hashCode()}|${proposal.kind}|${proposal.amountMinor}")
        val account = synchronized(paymentCache) {
            paymentCache.consume(source, current.generation, proposal, SystemClock.elapsedRealtime())
        }
        val event = CapturedNotification(source, profile, session, hash(text), System.currentTimeMillis(), null, current.generation)
        if (queue.trySend(Queued(event, text, origin, account)).isFailure) {
            diagnostic(source, "SAVE", "QUEUE_FULL"); mutableError.value = "事件队列已满，可能存在未采集记录。"
        } else diagnostic(source, "RULE", "QUEUED")
    }
    internal fun close() { scope.cancel(); liveNotification.cancel() }
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
    fun afterRestore() { sessions.clear(); synchronized(paymentCache) { paymentCache.clear() }; mutableDiagnostics.value = emptyList(); mutableLastEvent.value = null; mutableError.value = null }
    fun removed(sbn: StatusBarNotification) { sessions.remove(sbn.key) }
    fun posted(sbn: StatusBarNotification) {
        val current = settings.value
        if (method.value in setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.SHIZUKU) && current.enabled && sbn.packageName == ALIPAY && sbn.packageName in current.sources && sbn.user == Process.myUserHandle()) {
            diagnostic(sbn.packageName, "EVENT", "NOTIFICATION_EVENT")
            settledNotice(sbn.packageName, sbn.notification, "listener:${sbn.key}|${sbn.postTime}", method.value)
            return
        }
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
    private companion object { const val ALIPAY = "com.eg.android.AlipayGphone" }
}
