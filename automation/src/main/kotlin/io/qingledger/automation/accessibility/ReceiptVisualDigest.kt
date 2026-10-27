package io.qingledger.automation.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import io.qingledger.domain.model.OcrReceiptRegion

/** A bounded difference hash of the verified success/party/amount area, excluding status bar and ads. */
internal object ReceiptVisualDigest {
    fun from(bitmap: Bitmap, region: OcrReceiptRegion?): String? = runCatching {
        if (region == null) return null
        val top = (region.top / 16 * 16).coerceIn(0, bitmap.height - 1)
        val bottom = ((region.bottom + 15) / 16 * 16).coerceIn(top + 1, bitmap.height)
        val sample = Bitmap.createBitmap(17, 16, Bitmap.Config.ARGB_8888)
        try {
            Canvas(sample).drawBitmap(bitmap, Rect(0, top, bitmap.width, bottom), Rect(0, 0, 17, 16), Paint(Paint.FILTER_BITMAP_FLAG))
            fun light(x: Int, y: Int): Int { val c = sample.getPixel(x, y); return ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000 }
            val bytes = ByteArray(32)
            repeat(16) { y -> repeat(16) { x ->
                if (light(x, y) > light(x + 1, y)) { val bit = y * 16 + x; bytes[bit / 8] = (bytes[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte() }
            } }
            if (bytes.all { it == 0.toByte() }) null else bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        } finally { sample.recycle() }
    }.getOrNull()
}
