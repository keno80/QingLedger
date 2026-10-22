package io.qingledger.automation.accessibility

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.qingledger.domain.model.OcrReceiptLine
import io.qingledger.domain.model.OcrReceiptRules
import io.qingledger.domain.model.OcrPaymentPageRules

/** Bundled model; every input/crop remains in memory until its task completes. */
internal object LocalReceiptOcr {
    private var busy = false

    fun read(bitmap: Bitmap, complete: (AccessibilityScreenshotProbe.Result) -> Unit) {
        if (busy) { bitmap.recycle(); complete(AccessibilityScreenshotProbe.Result("OCR_BUSY")); return }
        busy = true
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
        catch (_: Exception) { bitmap.recycle(); busy = false; complete(AccessibilityScreenshotProbe.Result("OCR_FAILED")); return }
        var enlarged: Bitmap? = null
        var finished = false
        fun finish(lines: List<OcrReceiptLine>?) {
            if (finished) return
            finished = true
            val result = try {
                if (lines == null) AccessibilityScreenshotProbe.Result("OCR_FAILED") else evaluate(lines, bitmap.width, bitmap.height)
            } catch (_: Exception) { AccessibilityScreenshotProbe.Result("OCR_FAILED") }
            finally { recognizer.close(); enlarged?.recycle(); bitmap.recycle(); busy = false }
            complete(result)
        }
        fun refineAccountOrFinish(original: List<OcrReceiptLine>) {
            val page = OcrPaymentPageRules.evaluate(original, bitmap.width, bitmap.height)
            val label = original.singleOrNull { it.text.replace(Regex("\\s+"), "") == "付款方式" }
            if (page.context == null || page.context?.accountName != null || label == null) { finish(original); return }
            val bottom = minOf(bitmap.height, label.bottom + bitmap.height / 5,
                original.filter { it.top > label.bottom && Regex("使用|优惠|推荐|分期").containsMatchIn(it.text) }.minOfOrNull { it.top } ?: bitmap.height)
            if (bottom <= label.bottom) { finish(original); return }
            // Isolate the selected confirmation field, excluding the bank logo and recommendations.
            val left = bitmap.width * 16 / 100
            val right = bitmap.width * 92 / 100
            val crop = Bitmap.createBitmap(bitmap, left, label.bottom, right - left, bottom - label.bottom)
            val scaled = try { Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true) } finally { crop.recycle() }
            enlarged?.recycle(); enlarged = scaled
            recognizer.process(InputImage.fromBitmap(scaled, 0)).addOnCompleteListener { task ->
                try {
                    if (!task.isSuccessful) finish(original)
                    else {
                        val refined = lines(task.result).map {
                            it.copy(left = left + it.left / 3, right = left + it.right / 3, top = label.bottom + it.top / 3, bottom = label.bottom + it.bottom / 3)
                        }
                        finish((original.filter { it.top < label.bottom || it.top >= bottom } + refined).take(256))
                    }
                } catch (_: Exception) { finish(null) }
            }
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                try {
                    if (!task.isSuccessful) { finish(null); return@addOnCompleteListener }
                    val original = lines(task.result)
                    val payment = OcrPaymentPageRules.evaluate(original, bitmap.width, bitmap.height)
                    val controls = original.any { it.text.replace(" ", "").contains("使用密码") } && original.any { it.text.trim() in setOf("支付", "确认付款", "确认支付") }
                    if (controls && payment.context?.accountName == null && bitmap.width <= 1600 && bitmap.height <= 3200) {
                        val top = bitmap.height * 35 / 100
                        // Keep validated amount, merchant and field geometry from the original pass.
                        val field = original.singleOrNull { Regex("^付款方式(?:更改)?[\\p{P}\\p{S}]*$").matches(it.text.replace(Regex("\\s+"), "")) }
                        val keepAbove = if (payment.context != null && field != null) field.bottom else top
                        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
                        val scaled = try { Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true) } finally { crop.recycle() }
                        enlarged = scaled
                        recognizer.process(InputImage.fromBitmap(scaled, 0)).addOnCompleteListener { retry ->
                            try {
                                if (!retry.isSuccessful) refineAccountOrFinish(original)
                                else {
                                    val refined = lines(retry.result).filter { it.top >= 4 }.map {
                                        it.copy(left = it.left / 2, right = it.right / 2, top = top + it.top / 2, bottom = top + it.bottom / 2)
                                    }
                                    refineAccountOrFinish((original.filter { it.top < keepAbove } + refined.filter { it.top >= keepAbove }).take(256))
                                }
                            } catch (_: Exception) { finish(null) }
                        }
                    } else finish(original)
                } catch (_: Exception) { finish(null) }
            }
        } catch (_: Exception) { finish(null) }
    }

    private fun lines(text: Text) = text.textBlocks.flatMap { it.lines }.take(256).mapNotNull { line ->
        line.boundingBox?.let { OcrReceiptLine(line.text, it.left, it.top, it.right, it.bottom) }
    }

    private fun evaluate(lines: List<OcrReceiptLine>, width: Int, height: Int): AccessibilityScreenshotProbe.Result {
        val payment = OcrPaymentPageRules.evaluate(lines, width, height)
        if (payment.reason != "NOT_CONFIRMATION") return AccessibilityScreenshotProbe.Result(payment.reason,
            "本地识别 ${lines.size} 行 · 商户 ${payment.context?.merchant != null} · 账户 ${payment.context?.accountName != null} · 未保存图片或识别原文", fields = lines.size, paymentPage = payment)
        val parsed = OcrReceiptRules.evaluate(lines, height)
        val states = lines.count { it.text.contains("支付成功") || it.text.contains("付款成功") }
        return AccessibilityScreenshotProbe.Result(parsed.reason,
            "本地识别 ${lines.size} 行 · 成功字样 $states 行 · 商户 ${parsed.merchant != null} · 未保存图片或识别原文", parsed.receiptText, lines.size, merchantName = parsed.merchant)
    }
}
