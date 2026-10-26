package io.qingledger.automation.accessibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

internal fun ocrFixtureTypeface(): Typeface {
    val bundledSystemFont = java.io.File("/system/fonts/NotoSansCJK-Regular.ttc")
    return if (bundledSystemFont.isFile) Typeface.Builder(bundledSystemFont).setTtcIndex(2).build()
    else Typeface.create("sans-serif", Typeface.NORMAL)
}

class OcrPaymentContextTest {
    private suspend fun shapes(bitmap: Bitmap): List<String> {
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
        return try { withTimeout(20000) {
            suspendCancellableCoroutine { continuation -> recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
                val values = text.textBlocks.flatMap { it.lines }.map { line -> line.text.map { c ->
                    if (c.isDigit()) '#' else if (c in "付款方式更改使用密码支付银行储蓄卡信用零钱通优惠()*＊ØOoY￥¥（）.") c else '?'
                }.joinToString("") + ":" + line.boundingBox + (if (line.text.contains("银行")) " card-type=" + line.text.substringAfter("银行").replace(Regex("[0-9]"), "#") else "") +
                    (if (!line.text.contains("银行") && Regex(".*[0-9]{4}$").matches(line.text)) " masked-prefix=" + line.text.dropLast(4).map { it.code.toString(16) }.joinToString(",") else "") }
                if (continuation.isActive) continuation.resume(values)
            }.addOnFailureListener { if (continuation.isActive) continuation.resume(listOf("recognition failed")) } }
        } } finally { recognizer.close() }
    }
    private suspend fun fieldShapes(bitmap: Bitmap, topFraction: Int, offset: Int = 0, heightFraction: Int = 15): List<String> {
        val crop = Bitmap.createBitmap(bitmap, bitmap.width * 16 / 100, bitmap.height * topFraction / 100 + offset, bitmap.width * 76 / 100, bitmap.height * heightFraction / 100)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
        return try { shapes(scaled) } finally { scaled.recycle(); crop.recycle() }
    }
    private suspend fun panelShapes(bitmap: Bitmap): List<String> {
        val top = bitmap.height * 35 / 100
        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
        return try { shapes(scaled) } finally { scaled.recycle(); crop.recycle() }
    }
    private suspend fun read(bitmap: Bitmap) = withTimeout(20000) {
        suspendCancellableCoroutine { continuation -> LocalReceiptOcr.read(bitmap) { if (continuation.isActive) continuation.resume(it) } }
    }

    @Test fun generatedConfirmationSeparatesSelectedBankFromRecommendedBankAndDiscount() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 2200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f; typeface = ocrFixtureTypeface() }
        canvas.drawText("使用密码", 900f, 500f, paint)
        paint.textSize = 60f; canvas.drawText("DemoShop", 450f, 760f, paint)
        paint.textSize = 100f; canvas.drawText("¥28.00", 440f, 920f, paint)
        paint.textSize = 60f
        canvas.drawText("付款方式", 70f, 1100f, paint)
        canvas.drawText("测试银行储蓄卡", 200f, 1280f, paint)
        canvas.drawText("**** 1234", 200f, 1360f, paint)
        canvas.drawText("使用其他银行信用卡(5678)", 160f, 1580f, paint)
        canvas.drawText("优惠0.40元", 160f, 1650f, paint)
        canvas.drawText("支付", 550f, 2020f, paint)
        val metadata = shapes(bitmap) + fieldShapes(bitmap, 50)
        val result = read(bitmap)
        assertEquals("Synthetic shapes: $metadata", "PAYMENT_CONTEXT_CAPTURED", result.reason)
        assertEquals(2800L, result.paymentPage?.context?.amountMinor)
        assertEquals("DemoShop", result.paymentPage?.context?.merchant)
        assertTrue(result.paymentPage?.context?.accountName in setOf("测试银行储蓄卡（1234）", "测试银行（1234）"))
        assertNull(result.receiptText)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun privateDiscountConfirmationReadsOnlyCurrentAccount() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("discount_fixture") == "discount-confirm.png")
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val file = java.io.File(directory, "discount-confirm.png")
        try {
            val bitmap = BitmapFactory.decodeFile(file.path)!!
            val metadata = shapes(bitmap) + panelShapes(bitmap) + fieldShapes(bitmap, 70, 4, 20)
            val result = read(bitmap)
            assertEquals("Private confirmation fields: $metadata", "PAYMENT_CONTEXT_CAPTURED", result.reason)
            assertTrue("Only the selected private bank tail is accepted", result.paymentPage?.context?.accountName?.endsWith("（${args.getString("discount_expected_tail")}）") == true)
            args.getString("discount_expected_bank")?.let { assertEquals("Private bank name must match", it, result.paymentPage?.context?.accountName?.let(PaymentAccountRules::bankIdentity)?.first) }
            assertEquals(args.getString("discount_expected_minor")!!.toLong(), result.paymentPage?.context?.amountMinor)
            assertEquals(args.getString("discount_expected_merchant"), result.paymentPage?.context?.merchant)
            args.getString("discount_expected_original_minor")?.let { assertEquals(it.toLong(), result.paymentPage?.context?.originalAmountMinor) }
            args.getString("discount_expected_saving_minor")?.let { assertEquals(it.toLong(), result.paymentPage?.context?.discountMinor) }
            assertNull(result.receiptText)
        } finally { file.delete() }
    }

    @Test fun privateConfirmationAndReceiptFixturesAssociateWithoutTouchingRealLedger() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("payment_fixture") == "context-confirm.png")
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val confirmFile = java.io.File(directory, "context-confirm.png")
        val receiptFile = java.io.File(directory, "context-success.png")
        try {
            val bitmap = BitmapFactory.decodeFile(confirmFile.path)!!
            val metadata = shapes(bitmap) + fieldShapes(bitmap, 60)
            val confirmation = read(bitmap)
            val page = confirmation.paymentPage
            assertNotNull("Confirmation must yield a structured context; reason=${confirmation.reason}", page?.context)
            assertTrue("Only the selected private bank tail is accepted; ${confirmation.reason}; $metadata", page!!.context!!.accountName?.endsWith("（${args.getString("payment_expected_tail")}）") == true)
            args.getString("payment_expected_bank")?.let { assertEquals("Private bank name must match", it, page.context!!.accountName?.let(PaymentAccountRules::bankIdentity)?.first) }
            assertTrue("Private confirmation merchant must match", page.context!!.merchant == args.getString("payment_expected_merchant"))
            val receipt = read(BitmapFactory.decodeFile(receiptFile.path)!!)
            assertEquals("RECOGNIZED", receipt.reason)
            assertTrue("Private success merchant must match", receipt.merchantName == args.getString("payment_expected_merchant"))
            val cache = OcrPaymentContextCache()
            cache.observe("com.tencent.mm", 1, page, 1000)
            val proposal = AccessibilityRules.propose(receipt.receiptText!!)!!
            val context = cache.consume("com.tencent.mm", 1, proposal, receipt.merchantName, 2000)
            assertEquals(page.context, context)
            assertNull(cache.consume("com.tencent.mm", 1, proposal, receipt.merchantName, 2100))
        } finally { confirmFile.delete(); receiptFile.delete() }
    }
}
