package io.qingledger.automation.accessibility

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import io.qingledger.automation.notification.NotificationCaptureRuntime
import io.qingledger.automation.shizuku.ShizukuCaptureController
import io.qingledger.domain.model.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Shizuku changes the image transport; financial recognition and identity remain shared. */
class ShizukuReceiptProcessor(context: Context, private val runtime: NotificationCaptureRuntime) {
    private val tracker = ReceiptSessionTracker(Process.myUserHandle().toString(), AccessibilityPaymentStateStore::newSession)
    private val cache = OcrPaymentContextCache()
    private val store = AccessibilityPaymentStateStore(context)
    private var generation: Long? = null

    suspend fun process(frame: ShizukuCaptureController.Frame, validate: suspend (ShizukuCaptureController.Frame) -> Boolean) = withContext(Dispatchers.Main.immediate) {
        fun current() = runtime.method.value == CaptureMethod.SHIZUKU && runtime.settings.value.let {
            it.enabled && it.generation == frame.generation && frame.sourcePackage in it.sources
        }
        if (!validate(frame) || !current()) return@withContext
        if (generation != frame.generation) {
            store.restore(tracker, cache, System.currentTimeMillis())
            val history = runtime.accessibilityReceipts()
            tracker.reconcile(history.map { it.session }.toSet())
            history.forEach { tracker.seed(it.source, it.kind, it.amountMinor, it.merchant, it.session) }
            generation = frame.generation
        }
        if (!OcrReceiptRules.supportsPage(frame.sourcePackage, frame.activity)) {
            if (!PaymentCapturePhaseRules.isAuthenticationActivity(frame.activity) && (cache.snapshot() != null || tracker.state.pending != null)) {
                cache.clear(); tracker.clearPending(); store.save(tracker, cache)
            }
            runtime.diagnostic(frame.sourcePackage, "OCR", "OCR_UNSUPPORTED_PAGE"); return@withContext
        }
        val bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext
        val result = withTimeoutOrNull(10_000) { suspendCancellableCoroutine<AccessibilityScreenshotProbe.Result> { continuation ->
            LocalReceiptOcr.read(bitmap) { result -> if (continuation.isActive) continuation.resume(result) }
        } } ?: return@withContext
        if (!validate(frame) || !current()) return@withContext
        runtime.diagnostic(frame.sourcePackage, "OCR", result.reason, result.fields, result.details)
        val now = System.currentTimeMillis()
        result.paymentPage?.let { page ->
            cache.observe(frame.sourcePackage, frame.generation, page, now)
            page.context?.let { tracker.confirmation(frame.sourcePackage, it.amountMinor, it.merchant, frame.generation, now) }
            if (page.context == null && page.reason != "PAYMENT_METHOD_LIST") tracker.clearPending()
            store.save(tracker, cache)
            return@withContext
        }
        val text = result.receiptText
        if (text == null) {
            if (result.reason !in setOf("PAYMENT_AUTHENTICATION", "OCR_TRANSIENT_SURFACE", "OCR_BUSY", "OCR_TIMEOUT", "OCR_FAILED")) {
                cache.clear(); tracker.clearPending(); store.save(tracker, cache)
            }
            return@withContext
        }
        val proposal = AccessibilityRules.propose(text) ?: return@withContext
        val consumed = cache.consumeDetailed(frame.sourcePackage, frame.generation, proposal, result.merchantName, now)
        val context = consumed.context
        val merchant = result.merchantName ?: proposal.merchantName ?: context?.merchant
        val tracked = tracker.success(frame.sourcePackage, proposal, merchant, frame.generation, now, result.receiptVisual)
        store.save(tracker, cache)
        runtime.diagnostic(frame.sourcePackage, "RULE", if (tracked.revisit) "RECEIPT_REVISIT" else "RECEIPT_NEW")
        if (context?.accountName == null && !tracked.revisit) runtime.diagnostic(frame.sourcePackage, "RULE", consumed.reason)
        runtime.shizukuPosted(frame.sourcePackage, tracked.session, text, frame.generation, context?.accountName, merchant, context?.originalAmountMinor, context?.discountMinor)
    }
}
