package io.qingledger.automation.notification

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.domain.model.*
import io.qingledger.domain.repository.CaptureRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class AlipayCaptureRuntimeTest {
    @Test fun packagedAccessibilityConfigIncludesNotificationAndWindowEvents() {
        val xml = context.resources.getXml(io.qingledger.automation.R.xml.ledger_accessibility_service)
        xml.use {
            while (it.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) it.next()
            val types = it.getAttributeIntValue("http://schemas.android.com/apk/res/android", "accessibilityEventTypes", 0)
            assertTrue(types and android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED != 0)
            assertTrue(types and android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0)
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = "com.eg.android.AlipayGphone"
    private lateinit var repository: MemoryCapture
    private lateinit var runtime: NotificationCaptureRuntime
    @Before fun setup() = runBlocking {
        context.getSharedPreferences("capture_method", Context.MODE_PRIVATE).edit().putString("method", "ACCESSIBILITY").commit()
        repository = MemoryCapture(source)
        runtime = NotificationCaptureRuntime(context, repository)
        withTimeout(5000) { runtime.settings.first { it.enabled } }
        Unit
    }
    @After fun teardown() { runtime.close() }
    @Test fun diagnosticsExplainDisabledSourcesAndKeepBoundedMetadataOnly() = runBlocking {
        runtime.accessibilityEventReceived("com.tencent.mm", false)
        assertEquals("SOURCE_DISABLED", runtime.diagnostics.value.last().reason)
        runtime.accessibilityEventReceived("unrelated.app", false)
        assertEquals(1, runtime.diagnostics.value.size)
        repeat(60) { runtime.diagnostic(source, "READ", "PAGE_READ", it) }
        assertEquals(40, runtime.diagnostics.value.size)
        assertEquals(59, runtime.diagnostics.value.last().fields)
        repeat(10) { runtime.diagnostic(source, "RULE", "NO_SUCCESS_STATE") }
        assertEquals(1, runtime.diagnostics.value.count { it.reason == "NO_SUCCESS_STATE" })
        assertTrue(runtime.diagnostics.value.all { it.sourcePackage == source })
    }
    @Test fun recognizedAndRepeatedNoticesReportSavedAndDuplicateStages() = runBlocking {
        val notification = notice("你有一笔19.01元的支出，点此查看详情。")
        runtime.accessibilityNotificationPosted(source, notification, 10)
        withTimeout(5000) { runtime.diagnostics.first { it.any { d -> d.reason == "AUTO_POSTED" } } }
        runtime.accessibilityNotificationPosted(source, notification, 10)
        withTimeout(5000) { runtime.diagnostics.first { it.any { d -> d.reason == "DUPLICATE" } } }
        assertEquals(1, repository.received.value.size)
    }
    private fun notice(text: String, time: Long = 1000L) = Notification.Builder(context, "test")
        .setContentTitle(if (text.contains("退款")) "退款提醒" else "交易提醒")
        .setContentText(text).setWhen(time).build()
    @Test fun accessibilityModeConsumesExpenseAndRefundNoticesWithoutNotificationGrant() = runBlocking {
        runtime.accessibilityNotificationPosted(source, notice("你有一笔19.01元的支出，点此查看详情。"), 10)
        runtime.accessibilityNotificationPosted(source, notice("你收到一笔19.01元退款，点此查看账单详情！", 2000), 20)
        val rows = withTimeout(5000) { repository.received.first { it.size == 2 } }
        assertEquals(listOf(TransactionKind.EXPENSE, TransactionKind.REFUND), rows.map { it.second.kind })
        assertTrue(rows.all { it.second.amountMinor == 1901L })
    }
    @Test fun pageAndBothNotificationTransportsWriteOnlyOneBill() = runBlocking {
        val notification = notice("你有一笔19.01元的支出，点此查看详情。")
        runtime.accessibilityPosted(source, "page", "支付成功\n¥19.01", 4)
        runtime.accessibilityNotificationPosted(source, notification, 10)
        runtime.posted(StatusBarNotification(source, source, 1, null, Process.myUid(), 0, 0, notification, Process.myUserHandle(), 1000))
        withTimeout(5000) { repository.received.first { it.isNotEmpty() } }
        delay(400)
        assertEquals(1, repository.received.value.size)
    }
    @Test fun summariesAndUnknownNoticesAreIgnoredButExpandedReceiptIsParsed() = runBlocking {
        val text = "你有一笔19.01元的支出，点此查看详情。"
        val grouped = notice(text).apply { flags = flags or Notification.FLAG_GROUP_SUMMARY }
        runtime.accessibilityNotificationPosted(source, grouped, 1)
        runtime.accessibilityNotificationPosted(source, notice("本月支出19.01元", 2000), 2)
        delay(200)
        assertTrue(repository.received.value.isEmpty())
        val expanded = Notification.Builder(context, "test").setContentTitle("交易提醒")
            .setContentText("点击查看交易详情")
            .setStyle(Notification.BigTextStyle().bigText(text)).setWhen(3000).build()
        runtime.accessibilityNotificationPosted(source, expanded, 3)
        val rows = withTimeout(5000) { repository.received.first { it.isNotEmpty() } }
        assertEquals(1, rows.size)
        assertEquals(1901L, rows.single().second.amountMinor)
    }
    @Test fun separateSameAmountNoticesRemainSeparateAndOtherSourcesAreIgnored() = runBlocking {
        val text = "你有一笔19.01元的支出，点此查看详情。"
        runtime.accessibilityNotificationPosted("unselected.app", notice(text), 1)
        runtime.accessibilityNotificationPosted(source, notice(text, 1000), 2)
        runtime.accessibilityNotificationPosted(source, notice(text, 2000), 3)
        withTimeout(5000) { repository.received.first { it.size == 2 } }
        assertEquals(2, repository.received.value.map { it.first.sessionKey }.distinct().size)
        repository.settings.value = CaptureSettings(false, 5, setOf(source))
        withTimeout(5000) { runtime.settings.first { !it.enabled } }
        runtime.accessibilityNotificationPosted(source, notice(text, 3000), 4)
        delay(200)
        assertEquals(2, repository.received.value.size)
    }
    private class MemoryCapture(source: String) : CaptureRepository {
        override val settings = MutableStateFlow(CaptureSettings(true, 4, setOf(source)))
        override val candidates = MutableStateFlow<List<PendingCandidate>>(emptyList())
        override val pendingCount = MutableStateFlow(0)
        val received = MutableStateFlow<List<Pair<CapturedNotification, NotificationProposal>>>(emptyList())
        override fun overview(period: PeriodRange) = flowOf(CaptureOverview())
        override suspend fun capturedState(event: CapturedNotification) = if (received.value.any { it.first.sessionKey == event.sessionKey && it.first.digest == event.digest }) "AUTO_POSTED" else null
        override suspend fun capture(event: CapturedNotification, proposal: NotificationProposal): Boolean { received.value += event to proposal; return true }
        override suspend fun setEnabled(enabled: Boolean) { settings.value = settings.value.copy(enabled = enabled) }
        override suspend fun setSource(source: String, enabled: Boolean) = error("unused")
        override suspend fun confirm(id: String, draft: TransactionDraft): String = error("unused")
        override suspend fun ignore(id: String) = error("unused")
        override suspend fun merge(id: String, transactionId: String) = error("unused")
        override suspend fun clearResolved() = error("unused")
    }
}
