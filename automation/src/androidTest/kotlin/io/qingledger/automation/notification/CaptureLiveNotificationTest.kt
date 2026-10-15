package io.qingledger.automation.notification

import android.app.Notification
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CaptureLiveNotificationTest {
    private val notices = CaptureLiveNotification(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun processingRequestsPromotionAndHasFiniteLifetime() {
        val notice = notices.progressNotification()
        assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(15_000L, notice.timeoutAfter)
        assertTrue(notice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        if (Build.VERSION.SDK_INT >= 36) {
            assertTrue(notice.extras.getBoolean("android.requestPromotedOngoing"))
            assertTrue(notice.hasPromotableCharacteristics())
        }
    }
    @Test fun finishedNotificationIsDismissibleAndNotPromoted() {
        val notice = notices.resultNotification("AUTO_POSTED")
        assertEquals(0, notice.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(notice.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(notice.extras.getBoolean("android.requestPromotedOngoing"))
        assertEquals(30_000L, notice.timeoutAfter)
        assertEquals("识别完成 · 已入账", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, notice.visibility)
    }
}
