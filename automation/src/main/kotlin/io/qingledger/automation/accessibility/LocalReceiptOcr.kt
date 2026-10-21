package io.qingledger.automation.accessibility

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.qingledger.domain.model.OcrReceiptLine
import io.qingledger.domain.model.OcrReceiptRules

/** Bundled Chinese model. The caller transfers bitmap ownership until task completion. */
internal object LocalReceiptOcr {
    private var busy = false

    fun read(bitmap: Bitmap, complete: (AccessibilityScreenshotProbe.Result) -> Unit) {
        if (busy) { bitmap.recycle(); complete(AccessibilityScreenshotProbe.Result("OCR_BUSY")); return }
        busy = true
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
        catch (_: Exception) { bitmap.recycle(); busy = false; complete(AccessibilityScreenshotProbe.Result("OCR_FAILED")); return }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                val result = try {
                    if (!task.isSuccessful) AccessibilityScreenshotProbe.Result("OCR_FAILED")
                    else {
                        val lines = task.result.textBlocks.flatMap { it.lines }.take(256).mapNotNull { line ->
                            line.boundingBox?.let { OcrReceiptLine(line.text, it.left, it.top, it.right, it.bottom) }
                        }
                        val parsed = OcrReceiptRules.evaluate(lines, bitmap.height)
                        val states = lines.count { it.text.contains("支付成功") || it.text.contains("付款成功") }
                        val stateMetadata = lines.firstOrNull { it.text.contains("支付成功") || it.text.contains("付款成功") }?.let {
                            val normalized = it.text.replace(Regex("\\s|\\u200b"), "")
                            val marker = if (normalized.contains("支付成功")) "支付成功" else "付款成功"
                            " · 状态前后字符 ${normalized.indexOf(marker)}/${normalized.length - normalized.indexOf(marker) - marker.length} · 前字符类别 ${normalized.take(normalized.indexOf(marker)).take(4).map { char -> Character.getType(char) }} · 状态位置 ${it.top}/${bitmap.height}"
                        }.orEmpty()
                        AccessibilityScreenshotProbe.Result(parsed.reason, "本地识别 ${lines.size} 行 · 成功字样 $states 行$stateMetadata · 未保存图片或识别原文", parsed.receiptText, lines.size)
                    }
                } catch (_: Exception) { AccessibilityScreenshotProbe.Result("OCR_FAILED") }
                finally { recognizer.close(); bitmap.recycle(); busy = false }
                complete(result)
            }
        } catch (_: Exception) {
            recognizer.close(); bitmap.recycle(); busy = false
            complete(AccessibilityScreenshotProbe.Result("OCR_FAILED"))
        }
    }
}
