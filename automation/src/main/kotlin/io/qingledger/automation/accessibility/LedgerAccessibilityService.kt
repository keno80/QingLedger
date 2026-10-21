package io.qingledger.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.qingledger.automation.notification.CaptureHost
import io.qingledger.domain.model.*
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.*

class LedgerAccessibilityService : AccessibilityService() {
    private val runtime get() = (application as CaptureHost).captureRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var activePage: String? = null
    private var session = ""
    private val paymentCache = PaymentSessionCache()
    private var activeAccount: String? = null

    override fun onServiceConnected() { super.onServiceConnected(); runtime.accessibilityConnectionChanged(true) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event?.packageName?.toString() ?: return
        runtime.accessibilityEventReceived(source, event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
        if (!runtime.acceptsAccessibility(source)) { activePage = null; paymentCache.clear(); pending?.cancel(); return }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notice = event.parcelableData as? Notification
            if (notice != null) runtime.accessibilityNotificationPosted(source, notice, event.eventTime)
            else runtime.diagnostic(source, "READ", "NOTICE_PAYLOAD_UNAVAILABLE")
            return
        }
        if (pending?.isActive == true) return
        val eventWindow = event.windowId
        val eventDisplay = event.displayId
        val eventNode = event.source
        pending = scope.launch {
            delay(350)
            if (!runtime.acceptsAccessibility(source)) return@launch
            val generation = runtime.settings.value.generation
            try {
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
                val reading = chosen.reading ?: run { runtime.diagnostic(source, "READ", chosen.decision.reason); paymentCache.clear(); return@launch }
                val snapshot = reading.snapshot
                val text = snapshot.text
                runtime.diagnostic(source, "READ", "PAGE_READ", text.lines().count { it.isNotBlank() })
                val decision = chosen.decision
                val proposal = decision.proposal
                runtime.diagnostic(source, "RULE", decision.reason)
                if (proposal == null) {
                    runtime.observePaymentPage(source, generation, text, snapshot.selected)
                    paymentCache.observe(source, generation, text, snapshot.selected, SystemClock.elapsedRealtime())
                    activePage = null; activeAccount = null; return@launch
                }
                val identity = "$source|${reading.windowId}|${proposal.kind}|${proposal.amountMinor}"
                if (activePage != identity) {
                    activePage = identity; session = UUID.randomUUID().toString()
                    val remembered = paymentCache.consume(source, generation, proposal, SystemClock.elapsedRealtime())
                    activeAccount = proposal.accountName ?: remembered.takeIf { !text.contains("付款方式") }
                }
                runtime.accessibilityPosted(source, session, text, generation, activeAccount)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                runtime.reportAccessibilityError()
                runtime.diagnostic(source, "READ", "READ_FAILED")
            }
        }
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
    override fun onInterrupt() { pending?.cancel(); activePage = null; paymentCache.clear(); runtime.accessibilityConnectionChanged(false) }
    override fun onDestroy() { scope.cancel(); paymentCache.clear(); runtime.accessibilityConnectionChanged(false); super.onDestroy() }
}
