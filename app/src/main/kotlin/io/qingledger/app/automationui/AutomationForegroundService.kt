package io.qingledger.app.automationui

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.qingledger.app.MainActivity
import io.qingledger.app.QingLedgerApplication
import io.qingledger.automation.summary.DailySummaryController
import io.qingledger.automation.summary.DailySummaryNotification
import io.qingledger.domain.model.CaptureMethod
import io.qingledger.domain.model.CaptureSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One foreground notification hosts the summary; Shizuku retains a status if summary is off. */
class AutomationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as QingLedgerApplication).container
    private lateinit var summary: DailySummaryController
    private var settings: CaptureSettings? = null
    private var latest: Notification? = null
    private var foregroundId = 0
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        foreground(STATUS_ID, statusNotification())
        if (destroyed) return
        summary = DailySummaryController(this, container.ledgerRepository, container.captureRepository, publish = { value ->
            scope.launch { latest = value; renderNotification() }
        })
        summary.start()
        scope.launch {
            combine(container.captureRepository.settings, container.captureRuntime.method) { config, method -> config to method }
                .distinctUntilChanged().collect { (config, method) ->
                    settings = config
                    if (config.enabled && method == CaptureMethod.SHIZUKU) container.shizuku.start(config.sources, config.generation)
                    else container.shizuku.stop()
                    renderNotification()
                }
        }
    }
    private fun renderNotification() {
        if (destroyed) return
        val config = settings ?: return
        if (config.dailySummaryEnabled) {
            foreground(DailySummaryNotification.ID, latest ?: statusNotification("今日收支正在更新"))
        } else if (config.enabled && container.captureRuntime.method.value == CaptureMethod.SHIZUKU) foreground(STATUS_ID, statusNotification())
        else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun foreground(id: Int, notification: Notification) {
        val previous = foregroundId
        try {
            startForeground(id, notification); foregroundId = id
            if (previous != 0 && previous != id) getSystemService(NotificationManager::class.java).cancel(previous)
        } catch (_: RuntimeException) {
            destroyed = true; container.shizuku.stop(); scope.cancel(); stopSelf()
        }
    }
    private fun statusNotification(text: String = "自动记账服务正在运行"): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "自动记账运行", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val intent = PendingIntent.getActivity(this, STATUS_ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, STATUS_CHANNEL).setSmallIcon(io.qingledger.automation.R.drawable.ic_capture_notice)
            .setContentTitle("轻账").setContentText(text).setContentIntent(intent).setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { summary.refresh(); return START_STICKY }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true; if (::summary.isInitialized) summary.close(); container.shizuku.stop(); scope.cancel(); super.onDestroy()
    }
    companion object {
        const val STATUS_ID = 4104
        const val STATUS_CHANNEL = "capture_background_service"
        fun ensureStarted(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, AutomationForegroundService::class.java)); true
        }.getOrDefault(false)
    }
}
