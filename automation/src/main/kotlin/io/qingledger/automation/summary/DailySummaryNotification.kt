package io.qingledger.automation.summary

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import io.qingledger.domain.model.DailySummaryRules
import io.qingledger.domain.model.LedgerSummary

/** Ordinary low-importance ledger summary, independent of capture progress and service status. */
class DailySummaryNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun notificationsEnabled(): Boolean = try {
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    } catch (_: RuntimeException) { false }

    fun show(summary: LedgerSummary, pendingCount: Int) = safely {
        manager.notify(ID, notification(summary, pendingCount))
    }

    fun cancel() = safely { manager.cancel(ID) }

    internal fun contentIntent(): Intent = Intent().apply {
        component = ComponentName(context.packageName, "io.qingledger.app.MainActivity")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(OPEN_TODAY_LEDGER, true)
    }

    internal fun notification(summary: LedgerSummary, pendingCount: Int): Notification {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "今日收支", NotificationManager.IMPORTANCE_LOW).apply {
            description = "展示今日收支和待确认数量，点击打开今日账本。"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val publicVersion = base()
            .setContentText("解锁查看今日收支")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val text = DailySummaryRules.content(summary, pendingCount)
        return base()
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun base(): Notification.Builder = Notification.Builder(context, CHANNEL)
        .setSmallIcon(io.qingledger.automation.R.drawable.ic_capture_notice)
        .setContentTitle("今日收支")
        .setColor(Color.rgb(33, 139, 114))
        .setCategory(Notification.CATEGORY_STATUS)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setShowWhen(false)
        .setContentIntent(PendingIntent.getActivity(context, ID, contentIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: RuntimeException) { /* Permission revocation/OEM restrictions must not interrupt bookkeeping. */ }
    }

    companion object {
        const val ID = 4102
        const val CHANNEL = "daily_ledger_summary"
        const val OPEN_TODAY_LEDGER = "open_today_ledger"
    }
}
