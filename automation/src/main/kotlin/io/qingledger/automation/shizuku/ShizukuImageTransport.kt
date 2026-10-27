package io.qingledger.automation.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** A screenshot exists only in bounded process memory and a transient pipe. */
internal object ShizukuImageTransport {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_EDGE = 8192
    const val MAX_PIXELS = 16_000_000L

    fun readBitmap(descriptor: ParcelFileDescriptor, timeoutMillis: Long = 3500): Bitmap = descriptor.use {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        try {
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0 || Os.poll(arrayOf(poll), remaining.coerceAtMost(500).toInt()) == 0) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("截图传输超时")
                    continue
                }
                val count = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                if (count == 0) break
                if (output.size() + count > MAX_BYTES) throw IOException("截图超过大小上限")
                output.write(buffer, 0, count)
            }
            decodePng(output.toByteArray())
        } catch (error: android.system.ErrnoException) {
            throw IOException("截图流已关闭", error)
        }
    }

    fun decodePng(bytes: ByteArray): Bitmap {
        if (bytes.size !in 24..MAX_BYTES || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            throw IOException("截图为空或不是 PNG")
        }
        val header = ByteBuffer.wrap(bytes)
        val width = header.getInt(16)
        val height = header.getInt(20)
        if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE || width.toLong() * height > MAX_PIXELS) {
            throw IOException("截图尺寸超过上限")
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("截图无法解码")
    }

    fun isBlank(bitmap: Bitmap): Boolean {
        // A protected screenshot can be a valid PNG containing only a black surface.
        for (y in 1..10) for (x in 1..10) {
            if ((bitmap.getPixel(x * (bitmap.width - 1) / 11, y * (bitmap.height - 1) / 11) and 0x00ffffff) > 0x00080808) return false
        }
        return true
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
}
