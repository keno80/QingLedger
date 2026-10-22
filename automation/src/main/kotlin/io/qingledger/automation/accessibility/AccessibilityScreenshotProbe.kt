package io.qingledger.automation.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Captures into memory and optionally transfers bitmap ownership to local OCR. */
internal object AccessibilityScreenshotProbe {
    data class Result(val reason: String, val details: String? = null, val receiptText: String? = null, val fields: Int? = null, val merchantName: String? = null, val paymentPage: io.qingledger.domain.model.OcrPaymentPageRules.Result? = null)

    suspend fun capture(service: AccessibilityService, window: Int, display: Int, stillCurrent: () -> Boolean, recognize: Boolean = false, onImage: (Result) -> Unit = {}): Result {
        if (!stillCurrent()) return Result("SCREENSHOT_STALE")
        if (service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0)
            return Result("SCREENSHOT_NO_CAPABILITY")
        var imageReceived = false
        return withTimeoutOrNull(if (recognize) 10_000 else 5_000) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        screenshot.hardwareBuffer.use { buffer ->
                            if (!continuation.isActive) return
                            val result = try {
                                if (!stillCurrent()) Result("SCREENSHOT_STALE")
                                else if (buffer.width.toLong() * buffer.height > 16_000_000) Result("SCREENSHOT_TOO_LARGE")
                                else {
                                    val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                    if (hardware == null) Result("SCREENSHOT_FAILED")
                                    else try {
                                        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                        if (software == null) Result("SCREENSHOT_FAILED")
                                        else {
                                            val inspected = try { inspect(software) } catch (error: Exception) { software.recycle(); throw error }
                                            if (recognize && inspected.reason == "SCREENSHOT_READY") {
                                                imageReceived = true
                                                onImage(inspected)
                                                LocalReceiptOcr.read(software) { ocr ->
                                                    if (continuation.isActive) continuation.resume(if (stillCurrent()) ocr else Result("SCREENSHOT_STALE"))
                                                }
                                                return
                                            }
                                            software.recycle()
                                            inspected
                                        }
                                    } finally { hardware.recycle() }
                                }
                            } catch (_: Exception) { Result("SCREENSHOT_FAILED") }
                            continuation.resume(result)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resume(Result(failureReason(errorCode)))
                    }
                }
                try {
                    if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(window, service.mainExecutor, callback)
                    else service.takeScreenshot(display, service.mainExecutor, callback)
                } catch (_: SecurityException) {
                    continuation.resume(Result("SCREENSHOT_NO_ACCESS"))
                } catch (_: Exception) {
                    continuation.resume(Result("SCREENSHOT_FAILED"))
                }
            }
        } ?: Result(if (imageReceived) "OCR_TIMEOUT" else "SCREENSHOT_TIMEOUT")
    }

    internal fun inspect(bitmap: Bitmap): Result {
        var darkest = 255
        var brightest = 0
        // Sample a sparse grid without retaining or logging pixel values.
        for (row in 0 until 32) for (column in 0 until 32) {
            val x = ((column + 1) * bitmap.width / 33).coerceIn(0, bitmap.width - 1)
            val y = ((row + 1) * bitmap.height / 33).coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            val luminance = (((color shr 16) and 255) * 299 + ((color shr 8) and 255) * 587 + (color and 255) * 114) / 1000
            darkest = minOf(darkest, luminance)
            brightest = maxOf(brightest, luminance)
        }
        return Result(if (brightest - darkest >= 16) "SCREENSHOT_READY" else "SCREENSHOT_UNIFORM", "图像 ${bitmap.width}×${bitmap.height} · 仅内存检查")
    }

    internal fun failureReason(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "SCREENSHOT_NO_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "SCREENSHOT_RATE_LIMIT"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "SCREENSHOT_INVALID_TARGET"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "SCREENSHOT_INVALID_TARGET"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SCREENSHOT_SECURE"
        else -> "SCREENSHOT_FAILED"
    }
}
