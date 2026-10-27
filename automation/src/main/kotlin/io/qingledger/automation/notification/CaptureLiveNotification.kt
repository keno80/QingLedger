package io.qingledger.automation.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.qingledger.domain.model.CaptureNotice

/** One notification is updated in place; notification failures must never prevent bookkeeping. */
class CaptureLiveNotification(private val context: Context, private val notificationId: Int = ID) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var revision = 0L
    private var pendingCompletion: Runnable? = null
    fun notificationsEnabled(): Boolean = manager.areNotificationsEnabled()
    fun promotionAllowed(): Boolean = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()

    @Synchronized fun started(diagnostic: Boolean = false) = safely {
        clearPendingCompletion()
        manager.notify(notificationId, progressNotification(diagnostic))
    }

    internal fun progressNotification(diagnostic: Boolean = false): Notification {
        val builder = if (diagnostic) base("实时更新展示自检", "Debug 测试通知，8秒后结束，不生成账单。")
            else base("正在识别交易", "正在核对交易信息，请稍候。")
        builder
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setTimeoutAfter(15_000)
        if (Build.VERSION.SDK_INT >= 36) builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }).setShortCriticalText(if (diagnostic) "自检" else "识别中")
        return builder.build()
    }

    @Synchronized fun finished(state: String?) = safely {
        clearPendingCompletion()
        val promoted = promotionAllowed()
        manager.notify(notificationId, resultNotification(state, promoted))
        if (promoted) {
            val expectedRevision = revision
            val completion = Runnable {
                synchronized(this) {
                    if (revision == expectedRevision) {
                        pendingCompletion = null
                        safely {
                            // Respect dismissal; never resurrect a notice removed by the user.
                            if (manager.activeNotifications.any { it.id == notificationId && it.tag == null })
                                manager.notify(notificationId, resultNotification(state))
                        }
                    }
                }
            }
            pendingCompletion = completion
            handler.postDelayed(completion, 4_000)
        }
    }

    internal fun resultNotification(state: String?, promoted: Boolean = false): Notification {
        val notice = CaptureNotice.fromState(state)
        val builder = base(notice.title, notice.description)
            .setOngoing(promoted).setAutoCancel(!promoted).setTimeoutAfter(if (promoted) 5_000 else 30_000)
        if (Build.VERSION.SDK_INT >= 36 && promoted) builder
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .setShortCriticalText(when (state) {
                "AUTO_POSTED", "CONFIRMED" -> "已入账"
                "MERGED" -> "已关联"
                "PENDING" -> "待确认"
                "SKIPPED", "IGNORED" -> "已跳过"
                else -> "未完成"
            })
        return builder.build()
    }

    @Synchronized fun cancel() = safely { clearPendingCompletion(); manager.cancel(notificationId) }
    private fun clearPendingCompletion() {
        revision++
        pendingCompletion?.let(handler::removeCallbacks)
        pendingCompletion = null
    }

    private fun base(title: String, text: String): Notification.Builder {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "自动记账进度与结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "交易识别中显示实时进度，完成后显示入账、关联或跳过结果。"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(io.qingledger.automation.R.drawable.ic_capture_notice)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(Color.rgb(33, 139, 114))
            .setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply { if (intent != null) setContentIntent(PendingIntent.getActivity(context, 4101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) { /* Denied permission / OEM restrictions do not affect capture. */ }
    }

    companion object { const val ID = 4101; const val CHANNEL = "capture_live_updates" }
}
