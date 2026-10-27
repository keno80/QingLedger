package io.qingledger.automation.accessibility

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

class PaymentStateStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() { context.getSharedPreferences("accessibility_payment_sessions", 0).edit().clear().commit() }
    @After fun cleanup() { context.getSharedPreferences("accessibility_payment_sessions", 0).edit().clear().commit() }
    private fun tracker() = ReceiptSessionTracker("test", AccessibilityPaymentStateStore::newSession)
    @Test fun confirmationAndAccountSurviveRestartAndCompletedReceiptRetainsOnlyHashes() {
        val store = AccessibilityPaymentStateStore(context); val first = tracker(); val cache = OcrPaymentContextCache()
        first.confirmation("wechat", 450, "测试商户", 1, 1000)
        cache.observe("wechat", 1, OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", OcrPaymentContext(450, "测试商户", "测试银行储蓄卡（1234）")), 1000)
        store.save(first, cache)
        val second = tracker(); val restored = OcrPaymentContextCache(); store.restore(second, restored, 2000)
        val paid = NotificationProposal(450, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE")
        assertEquals("测试银行储蓄卡（1234）", restored.consume("wechat", 1, paid, "测试商户", 3000)?.accountName)
        val booked = second.success("wechat", paid, "测试商户", 1, 3000); store.save(second, restored)
        val third = tracker(); store.restore(third, OcrPaymentContextCache(), 4000)
        val repeated = third.success("wechat", paid, "测试商户", 2, 4000)
        assertTrue(repeated.revisit); assertEquals(booked.session, repeated.session)
        val json = context.getSharedPreferences("accessibility_payment_sessions", 0).getString("state", "")!!
        assertFalse(json.contains("测试商户")); assertFalse(json.contains("测试银行"))
    }
    @Test fun expiredContextAndCorruptStorageCannotProduceAnAccount() {
        val store = AccessibilityPaymentStateStore(context); val cache = OcrPaymentContextCache()
        cache.observe("wechat", 1, OcrPaymentPageRules.Result("PAYMENT_CONTEXT_CAPTURED", OcrPaymentContext(450, "测试", "微信零钱")), 1000)
        store.save(tracker(), cache)
        val expired = OcrPaymentContextCache(); store.restore(tracker(), expired, 121001); assertNull(expired.snapshot())
        context.getSharedPreferences("accessibility_payment_sessions", 0).edit().putString("state", "{broken").commit()
        store.restore(tracker(), expired, 2000); assertNull(expired.snapshot())
        assertTrue(PaymentCapturePhaseRules.isTransientSurface(408, 408))
        assertFalse(PaymentCapturePhaseRules.isTransientSurface(1200, 2608))
    }
    @Test fun privateOutgoingTransferReceiptIsRecognizedWithoutWritingTheRealLedger() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("transfer_fixture") == "transfer-success.png")
        val file = java.io.File(context.filesDir, "transfer-success.png")
        try {
            val bitmap = BitmapFactory.decodeFile(file.path)!!
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            val observedParty = try { withTimeout(20000) { suspendCancellableCoroutine { continuation ->
                recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
                    val party = text.textBlocks.flatMap { it.lines }.mapNotNull { WeChatOutgoingRules.counterparty(it.text) }.distinct().singleOrNull()
                    if (continuation.isActive) continuation.resume(party)
                }.addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            } } } finally { recognizer.close() }
            val result = withTimeout(20000) { suspendCancellableCoroutine { continuation -> LocalReceiptOcr.read(bitmap) { if (continuation.isActive) continuation.resume(it) } } }
            assertEquals("RECOGNIZED", result.reason)
            val proposal = AccessibilityRules.propose(result.receiptText!!)!!
            assertEquals(TransactionKind.EXPENSE, proposal.kind)
            assertEquals(args.getString("transfer_expected_minor")!!.toLong(), proposal.amountMinor)
            assertNotNull("A payer counterparty line must be read", observedParty)
            assertEquals(observedParty, result.merchantName)
            assertNotNull(result.receiptVisual)
            assertTrue(bitmap.isRecycled)
        } finally { file.delete() }
    }
    @Test fun visualDigestIgnoresStatusBarChangesAndKeepsBitmapOwnershipWithCaller() {
        val bitmap = android.graphics.Bitmap.createBitmap(600, 1000, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
            canvas.drawRect(100f, 250f, 250f, 400f, paint)
            val region = OcrReceiptRegion(200, 600)
            val first = ReceiptVisualDigest.from(bitmap, region)
            assertNotNull(first)
            canvas.drawRect(0f, 0f, 600f, 100f, paint)
            assertEquals(first, ReceiptVisualDigest.from(bitmap, region))
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }
}
