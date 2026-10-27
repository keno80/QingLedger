package io.qingledger.app.captureconfirmation

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.*
import io.qingledger.app.AppContainer
import io.qingledger.domain.model.TransactionDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns one editable window. Financial state remains in Room, never in the window. */
class ConfirmationCoordinator(private val app: Context, private val container: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val windows = app.getSystemService(WindowManager::class.java)
    private val prefs = app.getSharedPreferences("confirmation_presentations", Context.MODE_PRIVATE)
    private val seen = prefs.getStringSet("shown", emptySet()).orEmpty().toMutableSet()
    private val queue = ArrayDeque<String>()
    private var active: String? = null
    private var overlay: android.widget.FrameLayout? = null
    private var composition: ComposeView? = null
    private var backDispatcher: android.window.OnBackInvokedDispatcher? = null
    private var backCallback: android.window.OnBackInvokedCallback? = null
    private var owner: OverlayOwner? = null
    var foregroundActivities = 0
        private set
    val busy = MutableStateFlow(false)
    val failure = MutableStateFlow<String?>(null)

    fun activityStarted() { foregroundActivities++ }
    fun activityStopped() { foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0) }
    fun offer(id: String) { scope.launch {
        if (id in seen || container.captureRepository.candidate(id).first()?.state != "PENDING") return@launch
        seen.add(id)
        while (seen.size > 512) seen.remove(seen.first())
        prefs.edit().putStringSet("shown", seen.toSet()).apply()
        queue.addLast(id); pump()
    } }
    private suspend fun pump() {
        if (active != null || busy.value) return
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (container.captureRepository.candidate(id).first()?.state != "PENDING") continue
            val power = app.getSystemService(PowerManager::class.java)
            val unlocked = power.isInteractive && !app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (unlocked && Settings.canDrawOverlays(app)) {
                active = id; failure.value = null
                try { showOverlay(id); return } catch (_: Exception) { removeOverlay(); active = null }
            }
            if (unlocked && foregroundActivities > 0) {
                active = id; failure.value = null
                try { app.startActivity(confirmationIntent(id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
                catch (_: Exception) { active = null }
            }
            notifyPending(id)
        }
    }
    fun presentInActivity(id: String): Boolean {
        if (active != null && active != id) {
            if (id !in queue) queue.addLast(id)
            notifyPending(id)
            return false
        }
        removeOverlay(); active = id; failure.value = null
        manager.cancel("pending:$id", NOTIFICATION_ID)
        return true
    }
    fun closedWithoutResolution(id: String) {
        if (active == id) { removeOverlay(); active = null; scope.launch { pump() } }
    }
    fun ignore(id: String, done: () -> Unit = {}) = resolve(id, done) { container.captureRepository.ignore(id) }
    fun confirm(id: String, draft: TransactionDraft, accountName: String?, done: () -> Unit = {}) = resolve(id, done) {
        container.captureRepository.confirmWithAccount(id, draft.copy(occurredAt = System.currentTimeMillis()), accountName)
    }
    private fun resolve(id: String, done: () -> Unit, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; failure.value = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                manager.cancel("pending:$id", NOTIFICATION_ID)
                if (active == id) { removeOverlay(); active = null }
                done()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failure.value = if (error is IllegalArgumentException) error.message else "操作失败，请重试。"
            } finally { busy.value = false }
            if (active == null) pump()
        }
    }
    private fun showOverlay(id: String) {
        val lifecycle = OverlayOwner()
        val view = ComposeView(app).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setContent { ConfirmationWindow(container, id) { closedWithoutResolution(id) } }
            isFocusableInTouchMode = true
        }
        val root = object : android.widget.FrameLayout(app) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { ignore(id); return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply { addView(view, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            title = "轻账确认记账"
        }
        owner = lifecycle; overlay = root; composition = view
        windows.addView(root, layout)
        lifecycle.resume(); view.requestFocus()
        if (Build.VERSION.SDK_INT >= 33) root.post {
            if (overlay !== root) return@post
            backCallback = android.window.OnBackInvokedCallback { ignore(id) }
            backDispatcher = root.findOnBackInvokedDispatcher()
            backCallback?.let { backDispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, it) }
        }
    }
    private fun removeOverlay() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { runCatching { backDispatcher?.unregisterOnBackInvokedCallback(it) } }
        backCallback = null; backDispatcher = null
        overlay?.let { runCatching { windows.removeViewImmediate(it) } }
        composition?.disposeComposition(); composition = null
        overlay = null; owner?.close(); owner = null
    }
    private fun confirmationIntent(id: String) = Intent(app, ConfirmationActivity::class.java).putExtra(EXTRA_CANDIDATE_ID, id)
    private fun notifyPending(id: String) {
        val channel = NotificationChannel(CHANNEL, "待确认记账", NotificationManager.IMPORTANCE_HIGH).apply { description = "识别后的交易等待你编辑并确认" }
        manager.createNotificationChannel(channel)
        if (!manager.areNotificationsEnabled()) return
        val intent = PendingIntent.getActivity(app, id.hashCode(), confirmationIntent(id).setData(android.net.Uri.parse("qingledger://confirmation/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("轻账 · 有交易待确认").setContentText("解锁后查看并确认").build()
        val notice = Notification.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("有一笔交易待确认")
            .setContentText("点击编辑并确认入账").setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        runCatching { manager.notify("pending:$id", NOTIFICATION_ID, notice) }
    }
    companion object {
        const val EXTRA_CANDIDATE_ID = "capture_candidate_id"
        const val CHANNEL = "capture_confirmation"
        const val NOTIFICATION_ID = 4103
    }
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    init { saved.performAttach(); saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    fun resume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun close() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
}
