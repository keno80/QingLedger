package io.qingledger.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.junit.Assume.assumeTrue

class AccessibilityScreenshotProbeTest {
    @Test fun realReceiptFixtureWhenSuppliedIsParsedWithoutLoggingFinancialText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val fixture = arguments.getString("receipt_fixture")
        val expected = arguments.getString("receipt_expected_minor")?.toLongOrNull()
        assumeTrue(fixture == "receipt-fixture.png" && expected != null)
        val file = java.io.File(instrumentation.targetContext.filesDir, "receipt-fixture.png")
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path)
            assertNotNull("Private fixture missing", bitmap)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            val shapes = try { withTimeout(20_000) {
                suspendCancellableCoroutine { continuation -> recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap!!, 0)).addOnSuccessListener { text ->
                    val metadata = text.textBlocks.flatMap { it.lines }.map { line ->
                        line.text.map { if (it.isDigit()) '#' else if (it in "支付成功付款¥￥Y.") it else '?' }.joinToString("") + ":" + line.boundingBox
                    }
                    if (continuation.isActive) continuation.resume(metadata)
                }.addOnFailureListener { if (continuation.isActive) continuation.resume(listOf("recognition failed")) } }
            } } finally { recognizer.close() }
            val result = withTimeout(20_000) {
                suspendCancellableCoroutine { continuation -> LocalReceiptOcr.read(bitmap!!) { if (continuation.isActive) continuation.resume(it) } }
            }
            assertEquals("Private fixture character classes: $shapes", "RECOGNIZED", result.reason)
            assertTrue("Receipt amount must match the private expected value", io.qingledger.domain.model.AccessibilityRules.propose(result.receiptText!!)?.amountMinor == expected)
            assertTrue(bitmap!!.isRecycled)
        } finally { file.delete() }
    }
    @Test fun packagedServiceDeclaresScreenshotWithoutPretendingToBeAccessibilityTool() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.resources.getXml(io.qingledger.automation.R.xml.ledger_accessibility_service).use {
            while (it.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) it.next()
            val namespace = "http://schemas.android.com/apk/res/android"
            assertTrue(it.getAttributeBooleanValue(namespace, "canTakeScreenshot", false))
            assertFalse(it.getAttributeBooleanValue(namespace, "isAccessibilityTool", false))
        }
    }

    @Test fun pureBlackImageIsNotMistakenForReadablePageAndNoPixelsEscapeResult() {
        val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLACK)
            assertEquals("SCREENSHOT_UNIFORM", AccessibilityScreenshotProbe.inspect(bitmap).reason)
            for (x in 64 until 128) for (y in 0 until 256) bitmap.setPixel(x, y, Color.WHITE)
            val result = AccessibilityScreenshotProbe.inspect(bitmap)
            assertEquals("SCREENSHOT_READY", result.reason)
            assertEquals("图像 128×256 · 仅内存检查", result.details)
        } finally { bitmap.recycle() }
    }

    @Test fun screenshotFailuresRemainDistinctAndUnknownFailuresDoNotLeakMessages() {
        assertEquals("SCREENSHOT_SECURE", AccessibilityScreenshotProbe.failureReason(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW))
        assertEquals("SCREENSHOT_NO_ACCESS", AccessibilityScreenshotProbe.failureReason(AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
        assertEquals("SCREENSHOT_INVALID_TARGET", AccessibilityScreenshotProbe.failureReason(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW))
        assertEquals("SCREENSHOT_RATE_LIMIT", AccessibilityScreenshotProbe.failureReason(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT))
        assertEquals("SCREENSHOT_FAILED", AccessibilityScreenshotProbe.failureReason(-999))
    }

    @Test fun bundledChineseModelRecognizesReceiptAndIgnoresCouponAmounts() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 2200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 80f }
        canvas.drawText("支付成功", 300f, 350f, paint)
        canvas.drawText("¥28.00", 300f, 650f, paint)
        paint.textSize = 50f
        canvas.drawText("优惠券 1元", 200f, 1300f, paint)
        canvas.drawText("领取后变为4元满减", 200f, 1400f, paint)
        val debugRecognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
        val syntheticLines = try {
            withTimeout(20_000) {
                suspendCancellableCoroutine { continuation ->
                    debugRecognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
                        if (continuation.isActive) continuation.resume(text.textBlocks.flatMap { it.lines }.map { "${it.text}:${it.boundingBox}" })
                    }.addOnFailureListener { if (continuation.isActive) continuation.resume(listOf("synthetic recognition failed")) }
                }
            }
        } finally { debugRecognizer.close() }
        val result = withTimeout(20_000) {
            suspendCancellableCoroutine { continuation -> LocalReceiptOcr.read(bitmap) { if (continuation.isActive) continuation.resume(it) } }
        }
        assertEquals("Synthetic image lines: $syntheticLines", "RECOGNIZED", result.reason)
        assertEquals(2800L, io.qingledger.domain.model.AccessibilityRules.propose(result.receiptText!!)?.amountMinor)
        assertTrue(bitmap.isRecycled)
    }
}
