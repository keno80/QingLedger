package io.qingledger.automation.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import io.qingledger.domain.model.CaptureNotice

/** One notification is updated in place; notification failures must never prevent bookkeeping. */
class CaptureLiveNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun notificationsEnabled(): Boolean = manager.areNotificationsEnabled()
    fun promotionAllowed(): Boolean = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()

    fun started() = safely {
        manager.notify(ID, progressNotification())
    }

    internal fun progressNotification(): Notification {
        val builder = base("正在识别交易", "正在识别并保存账单，请稍候。")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setTimeoutAfter(15_000)
        if (Build.VERSION.SDK_INT >= 36) builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }).setShortCriticalText("识别中")
        return builder.build()
    }

    fun finished(state: String?) = safely {
        manager.notify(ID, resultNotification(state))
    }

    internal fun resultNotification(state: String?): Notification {
        val notice = CaptureNotice.fromState(state)
        return base(notice.title, notice.description)
            .setOngoing(false).setAutoCancel(true).setTimeoutAfter(30_000).build()
    }

    fun cancel() = safely { manager.cancel(ID) }

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
