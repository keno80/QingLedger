package io.qingledger.automation.accessibility

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.automation.notification.NotificationCaptureRuntime
import io.qingledger.automation.shizuku.ShizukuCaptureController
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.CaptureRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ShizukuReceiptProcessorTest {
    @Test fun leavingPaymentForChatClearsTheUnfinishedAccountSelection() = checkPage("com.tencent.mm.ui.chatting.ChattingUI", false)
    @Test fun passwordAuthenticationKeepsTheShortLivedSelection() = checkPage("com.tencent.mm.plugin.wallet.pay.ui.WalletCheckPwdUI", true)

    private fun checkPage(activity: String, retained: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccessibilityPaymentStateStore(context)
        val now = System.currentTimeMillis()
        val cache = OcrPaymentContextCache()
        cache.observe("com.tencent.mm", 4, OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", OcrPaymentContext(1888, "测试商户", "微信零钱")), now)
        val tracker = ReceiptSessionTracker("test", AccessibilityPaymentStateStore::newSession)
        tracker.confirmation("com.tencent.mm", 1888, "测试商户", 4, now)
        store.save(tracker, cache)
        context.getSharedPreferences("capture_method", Context.MODE_PRIVATE).edit().putString("method", "SHIZUKU").commit()
        val runtime = NotificationCaptureRuntime(context, MemoryCapture())
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            withTimeout(5000) { runtime.settings.first { it.enabled } }
            ShizukuReceiptProcessor(context, runtime).process(ShizukuCaptureController.Frame("com.tencent.mm", activity, 0, image, 4)) { true }
            val restored = OcrPaymentContextCache()
            val restoredTracker = ReceiptSessionTracker("test", AccessibilityPaymentStateStore::newSession)
            store.restore(restoredTracker, restored, System.currentTimeMillis())
            assertEquals(retained, restored.snapshot() != null)
            assertEquals(retained, restoredTracker.state.pending != null)
            assertFalse(image.isRecycled)
        } finally {
            image.recycle(); runtime.close()
            context.getSharedPreferences("accessibility_payment_sessions", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    private class MemoryCapture : CaptureRepository {
        override val settings = MutableStateFlow(CaptureSettings(true, 4, setOf("com.tencent.mm")))
        override val candidates = flowOf(emptyList<PendingCandidate>())
        override val pendingCount = flowOf(0)
        override fun overview(period: PeriodRange) = flowOf(CaptureOverview())
        override suspend fun setEnabled(enabled: Boolean) { settings.value = settings.value.copy(enabled = enabled) }
        override suspend fun setSource(source: String, enabled: Boolean) = Unit
        override suspend fun capture(event: CapturedNotification, proposal: NotificationProposal) = error("No receipt expected")
        override suspend fun capturedState(event: CapturedNotification): String? = null
        override suspend fun confirm(id: String, draft: TransactionDraft) = error("No confirmation expected")
        override suspend fun ignore(id: String) = Unit
        override suspend fun merge(id: String, transactionId: String) = Unit
        override suspend fun clearResolved() = Unit
    }
}
