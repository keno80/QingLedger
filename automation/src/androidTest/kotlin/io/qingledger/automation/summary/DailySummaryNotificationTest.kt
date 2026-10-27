package io.qingledger.automation.summary

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.automation.notification.CaptureLiveNotification
import io.qingledger.domain.model.LedgerSummary
import org.junit.Assert.*
import org.junit.Test

class DailySummaryNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notices = DailySummaryNotification(context)

    @Test fun summaryHasAnIndependentQuietChannelAndNoLiveUpdatePromotion() {
        val notice = notices.notification(LedgerSummary(45_600, 12_300), 2)
        assertNotEquals(CaptureLiveNotification.ID, DailySummaryNotification.ID)
        assertNotEquals(CaptureLiveNotification.CHANNEL, notice.channelId)
        assertEquals(DailySummaryNotification.CHANNEL, notice.channelId)
        assertTrue(notice.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notice.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(0L, notice.timeoutAfter)
        assertEquals("支出 ¥123.00 · 收入 ¥456.00 · 待确认 2 笔", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(DailySummaryNotification.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test fun lockScreenPublicVersionHidesAllAmountsAndPendingCounts() {
        val notice = notices.notification(LedgerSummary(45_600, 12_300), 2)
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
        assertNotNull(notice.publicVersion)
        val publicText = notice.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("解锁查看今日收支", publicText)
        assertFalse(publicText.contains("¥"))
        assertFalse(publicText.contains("2"))
    }

    @Test fun tappingSummaryExplicitlyOpensTodayLedger() {
        val intent = notices.contentIntent()
        assertEquals("io.qingledger.app.MainActivity", intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertTrue(intent.getBooleanExtra("open_today_ledger", false))
        assertNotNull(notices.notification(LedgerSummary(), 0).contentIntent)
    }
}
