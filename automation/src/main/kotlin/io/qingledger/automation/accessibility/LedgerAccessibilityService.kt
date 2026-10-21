package io.qingledger.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.qingledger.automation.notification.CaptureHost
import io.qingledger.domain.model.AccessibilityRules
import io.qingledger.domain.model.PaymentSessionCache
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
        pending = scope.launch {
            delay(350)
            if (!runtime.acceptsAccessibility(source)) return@launch
            val generation = runtime.settings.value.generation
            try {
                val root = rootInActiveWindow ?: run { runtime.diagnostic(source, "READ", "ROOT_UNAVAILABLE"); return@launch }
                if (root.packageName?.toString() != source) { runtime.diagnostic(source, "READ", "SOURCE_MISMATCH"); paymentCache.clear(); return@launch }
                val snapshot = visibleText(root)
                val text = snapshot.text
                runtime.diagnostic(source, "READ", "PAGE_READ", text.lines().count { it.isNotBlank() })
                val decision = AccessibilityRules.evaluate(text)
                val proposal = decision.proposal
                runtime.diagnostic(source, "RULE", decision.reason)
                if (proposal == null) {
                    runtime.observePaymentPage(source, generation, text, snapshot.selected)
                    paymentCache.observe(source, generation, text, snapshot.selected, SystemClock.elapsedRealtime())
                    activePage = null; activeAccount = null; return@launch
                }
                val identity = "$source|${root.windowId}|${proposal.kind}|${proposal.amountMinor}"
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
    private data class Snapshot(val text: String, val selected: List<String>)
    private fun visibleText(root: AccessibilityNodeInfo): Snapshot {
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        val fields = linkedSetOf<String>()
        val selected = linkedSetOf<String>()
        var nodes = 0
        var length = 0
        while (stack.isNotEmpty() && nodes++ < 256 && length < 8192) {
            val (node, depth) = stack.removeLast()
            if (node.isPassword || !node.isVisibleToUser || node.packageName?.toString() != root.packageName?.toString()) continue
            if ((node.isCheckable && node.isChecked) || node.isSelected) node.text?.toString()?.take(512)?.let { selected.add(it) }
            listOf(node.text, node.contentDescription).forEach { value ->
                val item = value?.toString()?.trim()?.take(512)
                if (!item.isNullOrBlank() && fields.add(item)) length += item.length + 1
            }
            if (depth < 20) for (index in (minOf(node.childCount, 32) - 1) downTo 0) node.getChild(index)?.let { stack.add(it to depth + 1) }
        }
        return Snapshot(fields.joinToString("\n").take(8192), selected.toList())
    }
    override fun onInterrupt() { pending?.cancel(); activePage = null; paymentCache.clear(); runtime.accessibilityConnectionChanged(false) }
    override fun onDestroy() { scope.cancel(); paymentCache.clear(); runtime.accessibilityConnectionChanged(false); super.onDestroy() }
}
