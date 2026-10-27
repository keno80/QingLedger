package io.qingledger.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.qingledger.automation.notification.CaptureHost
import io.qingledger.domain.model.*
import android.os.SystemClock
import kotlinx.coroutines.*

class LedgerAccessibilityService : AccessibilityService() {
    private val runtime get() = (application as CaptureHost).captureRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var activePage: String? = null
    private var session = ""
    private val paymentCache = PaymentSessionCache()
    private val ocrPaymentCache = OcrPaymentContextCache()
    private val receiptTracker by lazy { ReceiptSessionTracker(android.os.Process.myUserHandle().toString(), AccessibilityPaymentStateStore::newSession) }
    private val stateStore by lazy { AccessibilityPaymentStateStore(this) }
    private var stateLoaded = false
    private var historyLoaded = false
    private var contextExpiry: Job? = null
    private val screenshotGate = AccessibilityScreenshotGate(cooldownMs = 2_000)
    private var activeAccount: String? = null
    private var activeMerchant: String? = null
    private var activePrices = PaymentDiscount()
    private var pageWindow = -1
    private var pageClass: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        stateStore.restore(receiptTracker, ocrPaymentCache, System.currentTimeMillis()); stateLoaded = true
        scheduleContextExpiry()
        runCatching { persistState() }.onFailure { runtime.reportAccessibilityError() }
        runtime.accessibilityConnectionChanged(true)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event?.packageName?.toString() ?: return
        runtime.accessibilityEventReceived(source, event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
        if (!runtime.acceptsAccessibility(source)) { activePage = null; pageWindow = -1; pageClass = null; paymentCache.clear(); runCatching { clearOcrContext() }.onFailure { runtime.reportAccessibilityError() }; pending?.cancel(); return }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notice = event.parcelableData as? Notification
            if (notice != null) runtime.accessibilityNotificationPosted(source, notice, event.eventTime)
            else runtime.diagnostic(source, "READ", "NOTICE_PAYLOAD_UNAVAILABLE")
            return
        }
        if (pageWindow != event.windowId) { pageWindow = event.windowId; pageClass = null }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.className?.toString()?.startsWith("com.tencent.mm.") == true) pageClass = event.className.toString()
        if (pending?.isActive == true) return
        val eventWindow = event.windowId
        val eventDisplay = event.displayId
        val eventNode = event.source
        pending = scope.launch {
            delay(350)
            if (!runtime.acceptsAccessibility(source)) return@launch
            val generation = runtime.settings.value.generation
            try {
                ensureReceiptHistory()
                val roots = linkedMapOf<Int, AccessibilityNodeInfo>()
                fun addRoot(root: AccessibilityNodeInfo?) {
                    if (root?.packageName?.toString() == source) roots.putIfAbsent(root.windowId, root)
                }
                val displayWindows = windowsOnAllDisplays.get(eventDisplay).orEmpty()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.id == eventWindow }.thenByDescending { it.isFocused }.thenByDescending { it.layer })
                displayWindows.take(8).forEach { addRoot(it.root) }
                val activeRoot = rootInActiveWindow
                if (activeRoot?.window?.displayId == eventDisplay || activeRoot?.windowId == eventWindow) addRoot(activeRoot)
                if (eventNode?.refresh() == true) {
                    var node = eventNode
                    var depth = 0
                    while (depth++ < 20) { val parent = node?.parent ?: break; if (parent.packageName?.toString() != source) break; node = parent }
                    addRoot(node)
                }
                val readings = roots.values.take(4).map { root ->
                    val read = AccessibilityTreeReader.read(AndroidPageNode(root), source)
                    val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
                    runtime.diagnostic(source, "READ", "WINDOW_READ", read.text.lines().count { it.isNotBlank() }, "屏幕 $eventDisplay · 窗口 ${root.windowId} · 范围 ${bounds.width()}×${bounds.height()} · 同屏候选 ${roots.size} · ${read.stats.summary()}")
                    AccessibleWindowReading(root.windowId, read)
                }
                val chosen = AccessibilityWindowRules.choose(readings)
                val reading = chosen.reading ?: run { runtime.diagnostic(source, "READ", chosen.decision.reason); return@launch }
                val snapshot = reading.snapshot
                var text = snapshot.text
                runtime.diagnostic(source, "READ", "PAGE_READ", text.lines().count { it.isNotBlank() })
                var decision = chosen.decision
                var image: AccessibilityScreenshotProbe.Result? = null
                if (text.isBlank()) {
                    image = probeEmptyPage(source, generation, reading.windowId, eventDisplay) ?: return@launch
                    val paymentPage = image.paymentPage
                    if (paymentPage != null) {
                        ocrPaymentCache.observe(source, generation, paymentPage, System.currentTimeMillis())
                        if (paymentPage.context != null) {
                            paymentCache.clear()
                            val context = paymentPage.context!!
                            receiptTracker.confirmation(source, context.amountMinor, context.merchant, generation, System.currentTimeMillis())
                            scheduleContextExpiry()
                        } else if (paymentPage.reason != "PAYMENT_METHOD_LIST") { contextExpiry?.cancel(); receiptTracker.clearPending(); paymentCache.clear() }
                        persistState()
                        activePage = null; activeAccount = null; activeMerchant = null; activePrices = PaymentDiscount()
                        return@launch
                    }
                    if (image.receiptText == null) {
                        if (!image.reason.startsWith("SCREENSHOT_") && image.reason !in setOf("OCR_BUSY", "OCR_TIMEOUT", "OCR_FAILED", "OCR_TRANSIENT_SURFACE", "PAYMENT_AUTHENTICATION")) clearOcrContext()
                        return@launch
                    }
                    text = image.receiptText
                    decision = AccessibilityRules.evaluate(text)
                }
                val proposal = decision.proposal
                runtime.diagnostic(source, "RULE", decision.reason)
                if (proposal == null) {
                    if (PaymentCapturePhaseRules.isAuthentication(text) && !PaymentCapturePhaseRules.isFailure(text)) {
                        runtime.diagnostic(source, "RULE", "PAYMENT_AUTHENTICATION"); return@launch
                    }
                    val confirmation = PaymentCapturePhaseRules.nodeConfirmation(text)
                    if (!text.contains("选择付款方式")) clearOcrContext(clearFlow = confirmation == null)
                    runtime.observePaymentPage(source, generation, text, snapshot.selected)
                    paymentCache.observe(source, generation, text, snapshot.selected, SystemClock.elapsedRealtime())
                    if (confirmation != null) {
                        val account = if (snapshot.selected.isNotEmpty()) snapshot.selected.map(PaymentAccountRules::canonicalName).distinct().singleOrNull()
                            else PaymentAccountRules.extract(text, TransactionKind.EXPENSE)
                        val merchant = text.lines().mapNotNull(WeChatOutgoingRules::counterparty).distinct().singleOrNull()
                        val context = confirmation.copy(accountName = account, merchant = merchant)
                        ocrPaymentCache.observe(source, generation, OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", context), System.currentTimeMillis())
                        receiptTracker.confirmation(source, context.amountMinor, merchant, generation, System.currentTimeMillis())
                        scheduleContextExpiry(); persistState()
                    }
                    activePage = null; activeAccount = null; activeMerchant = null; activePrices = PaymentDiscount(); return@launch
                }
                val identity = "$source|${reading.windowId}|${proposal.kind}|${proposal.amountMinor}"
                if (activePage != identity) {
                    activePage = identity
                    val remembered = paymentCache.consume(source, generation, proposal, SystemClock.elapsedRealtime())
                    val consumed = ocrPaymentCache.consumeDetailed(source, generation, proposal, image?.merchantName ?: proposal.merchantName, System.currentTimeMillis())
                    val context = consumed.context
                    contextExpiry?.cancel()
                    activeAccount = proposal.accountName ?: context?.accountName ?: remembered.takeIf { !text.contains("付款方式") }
                    activeMerchant = image?.merchantName ?: proposal.merchantName ?: context?.merchant
                    activePrices = PaymentDiscount(context?.originalAmountMinor, context?.discountMinor)
                    val tracked = receiptTracker.success(source, proposal, activeMerchant, generation, System.currentTimeMillis(), image?.receiptVisual)
                    session = tracked.session; persistState()
                    runtime.diagnostic(source, "RULE", if (tracked.revisit) "RECEIPT_REVISIT" else "RECEIPT_NEW")
                    if (activeAccount == null && !tracked.revisit) runtime.diagnostic(source, "RULE", consumed.reason)
                    if (context != null) runtime.diagnostic(source, "RULE", "PAYMENT_CONTEXT_MATCHED", details = "商户 ${activeMerchant != null} · 账户 ${activeAccount != null}")
                }
                runtime.accessibilityPosted(source, session, text, generation, activeAccount, activeMerchant, activePrices.originalAmountMinor, activePrices.discountMinor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                runtime.reportAccessibilityError()
                runtime.diagnostic(source, "READ", "READ_FAILED")
            }
        }
    }
    private suspend fun ensureReceiptHistory() {
        if (!stateLoaded) { stateStore.restore(receiptTracker, ocrPaymentCache, System.currentTimeMillis()); stateLoaded = true }
        if (!historyLoaded) {
            val history = runtime.accessibilityReceipts()
            receiptTracker.reconcile(history.map { it.session }.toSet())
            history.forEach { receiptTracker.seed(it.source, it.kind, it.amountMinor, it.merchant, it.session) }
            persistState(); historyLoaded = true
        }
    }
    private fun persistState() { if (stateLoaded) stateStore.save(receiptTracker, ocrPaymentCache) }
    private fun scheduleContextExpiry() {
        contextExpiry?.cancel()
        val context = ocrPaymentCache.snapshot() ?: return
        val remaining = (120_001 - (System.currentTimeMillis() - context.at)).coerceIn(1, 120_001)
        contextExpiry = scope.launch { delay(remaining); ocrPaymentCache.clear("PAYMENT_CONTEXT_EXPIRED"); receiptTracker.clearPending(); paymentCache.clear(); runCatching { persistState() }.onFailure { runtime.reportAccessibilityError() } }
    }
    private fun clearOcrContext(clearFlow: Boolean = true) {
        contextExpiry?.cancel(); ocrPaymentCache.clear()
        if (clearFlow) { receiptTracker.clearPending(); paymentCache.clear() }
        persistState()
    }
    private suspend fun probeEmptyPage(source: String, generation: Long, window: Int, display: Int): AccessibilityScreenshotProbe.Result? {
        fun stillCurrent(): Boolean {
            if (!runtime.acceptsAccessibility(source) || runtime.settings.value.generation != generation) return false
            if (!getSystemService(android.os.PowerManager::class.java).isInteractive || getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return false
            return windowsOnAllDisplays.get(display).orEmpty().any {
                it.id == window && it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused && it.root?.packageName?.toString() == source
            }
        }
        // Recents/app transition events can precede focus becoming stable.
        var retries = 0
        while (!stillCurrent() && retries++ < 3) delay(350)
        if (!stillCurrent()) {
            val interactive = getSystemService(android.os.PowerManager::class.java).isInteractive
            val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
            val focused = windowsOnAllDisplays.get(display).orEmpty().any { it.id == window && it.isFocused }
            runtime.diagnostic(source, "IMAGE", "SCREENSHOT_NOT_READY", details = "屏幕交互 $interactive · 锁屏 $locked · 来源窗口聚焦 $focused")
            return null
        }
        if (!screenshotGate.request(source, display, window, true, true, SystemClock.elapsedRealtime())) {
            delay(2_000)
            if (!screenshotGate.request(source, display, window, true, stillCurrent(), SystemClock.elapsedRealtime())) return null
        }
        runtime.diagnostic(source, "IMAGE", "SCREENSHOT_REQUESTED")
        val recognize = OcrReceiptRules.supportsPage(source, pageClass.takeIf { pageWindow == window })
        val result = AccessibilityScreenshotProbe.capture(this, window, display, ::stillCurrent, recognize = recognize) {
            runtime.diagnostic(source, "IMAGE", it.reason, details = it.details)
        }
        runtime.diagnostic(source, if (result.reason.startsWith("SCREENSHOT_")) "IMAGE" else "OCR", result.reason, result.fields, result.details)
        if (!recognize && source == "com.tencent.mm" && result.reason == "SCREENSHOT_READY") runtime.diagnostic(source, "OCR", "OCR_UNSUPPORTED_PAGE")
        if (result.receiptText == null) { activePage = null; activeAccount = null; activeMerchant = null; activePrices = PaymentDiscount() }
        if (!recognize && source == "com.tencent.mm" && !PaymentCapturePhaseRules.isAuthenticationActivity(pageClass)) clearOcrContext()
        return result
    }
    private class AndroidPageNode(private val node: AccessibilityNodeInfo) : AccessiblePageNode {
        override val nodeType get() = node.className?.toString()
        override val sensitive get() = android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive
        override val sourcePackage get() = node.packageName?.toString()
        override val visible get() = node.isVisibleToUser
        override val password get() = node.isPassword
        override val text get() = node.text?.toString()
        override val description get() = node.contentDescription?.toString()
        override val selected get() = (node.isCheckable && node.isChecked) || node.isSelected
        override val childCount get() = node.childCount
        override fun child(index: Int): AccessiblePageNode? = node.getChild(index)?.let(::AndroidPageNode)
    }
    override fun onInterrupt() { pending?.cancel(); activePage = null; pageWindow = -1; pageClass = null; paymentCache.clear(); contextExpiry?.cancel(); ocrPaymentCache.clear(); runtime.accessibilityConnectionChanged(false) }
    override fun onDestroy() { scope.cancel(); paymentCache.clear(); ocrPaymentCache.clear(); runtime.accessibilityConnectionChanged(false); super.onDestroy() }
}
