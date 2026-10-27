package io.qingledger.automation.shizuku

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.Parcel
import android.os.Parcelable
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuImageTransportTest {
    @Test fun returningImageOverBinderClosesSenderDescriptor() {
        val pipe = ParcelFileDescriptor.createPipe()
        val parcel = Parcel.obtain()
        try {
            val result = ShizukuCaptureProtocol.imageResult(android.os.Bundle(), pipe[0])
            result.writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            assertFalse("发送方读取端必须在Binder结果写入Parcel后确定关闭，不能依赖GC", pipe[0].fileDescriptor.valid())
        } finally { parcel.recycle(); pipe.forEach { it.close() } }
    }

    @Test fun typedResultPreservesMetadataAndTheReceiverDescriptor() {
        val pipe = ParcelFileDescriptor.createPipe()
        val parcel = Parcel.obtain()
        var received: ReadOnlyCaptureResult? = null
        try {
            val metadata = android.os.Bundle().apply { putString("status", "ok") }
            ShizukuCaptureProtocol.imageResult(metadata, pipe[0]).writeToParcel(parcel, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
            parcel.setDataPosition(0)
            received = ReadOnlyCaptureResult.CREATOR.createFromParcel(parcel)
            assertEquals("ok", received.metadata.getString("status"))
            assertTrue(received.image!!.fileDescriptor.valid())
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { received?.image?.close(); parcel.recycle(); pipe.forEach { it.close() } }
    }

    @Test fun decodesPngFromPipeAndClosesReader() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = Thread { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) } }
        writer.start()
        val result = ShizukuImageTransport.readBitmap(pipe[0])
        assertEquals(20, result.width)
        assertEquals(Color.WHITE, result.getPixel(10, 10))
        result.recycle()
        writer.join(1000)
        assertFalse(writer.isAlive)
        assertFalse(pipe[0].fileDescriptor.valid())
    }

    @Test fun idlePipeTimesOutAndClosesReader() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertThrows(java.io.IOException::class.java) { ShizukuImageTransport.readBitmap(pipe[0], timeoutMillis = 30) }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally { pipe[1].close() }
    }

    @Test fun rejectsOversizedPngHeaderBeforeAllocatingBitmap() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        // IHDR width = 0x7fffffff, invalid CRC is irrelevant: bounds are checked first.
        bytes[16] = 0x7f; bytes[17] = 0xff.toByte(); bytes[18] = 0xff.toByte(); bytes[19] = 0xff.toByte()
        val error = assertThrows(java.io.IOException::class.java) { ShizukuImageTransport.decodePng(bytes) }
        assertEquals("截图尺寸超过上限", error.message)
    }
}
