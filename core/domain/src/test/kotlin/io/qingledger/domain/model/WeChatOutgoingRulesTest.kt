package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class WeChatOutgoingRulesTest {
    @Test fun observedContainerIsAllowedButChatAndImageViewerRemainExcluded() {
        assertTrue(OcrReceiptRules.supportsPage("com.tencent.mm", "com.tencent.mm.framework.app.UIPageFragmentActivity"))
        assertFalse(OcrReceiptRules.supportsPage("com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI"))
        assertFalse(OcrReceiptRules.supportsPage("com.tencent.mm", "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"))
    }
    @Test fun sentTransferWaitingForRecipientIsAnExpenseAndGetsACanonicalCounterparty() {
        val proposal = AccessibilityRules.propose("支付成功\n待测试对象确认收款\n¥1.00\n完成")
        assertNotNull(proposal)
        assertEquals(TransactionKind.EXPENSE, proposal?.kind)
        assertEquals(100L, proposal?.amountMinor)
        assertEquals("转账给测试对象", proposal?.merchantName)
        assertEquals("转账给测试对象", WeChatOutgoingRules.counterparty("向测试对象转账"))
        assertEquals(100L, AccessibilityRules.propose("支付成功\n待测试对象确认收款\n¥\n1.00\n完成")?.amountMinor)
    }
    @Test fun explicitSentRedPacketIsAnExpenseButChatCardsAndReceivedMoneyAreNot() {
        val proposal = AccessibilityRules.propose("红包已发出\n红包发给测试对象\n¥2.00\n完成")
        assertEquals(TransactionKind.EXPENSE, proposal?.kind)
        assertEquals(200L, proposal?.amountMinor)
        listOf("微信红包\n恭喜发财，大吉大利\n¥2.00\n已领取", "转账\n¥1.00\n待收款", "聊天记录\n红包已发出\n¥2.00", "转账成功\n¥1.00\n发送消息", "转账成功\n¥1.00", "转账详情\n转账成功\n¥1.00").forEach {
            assertNull(it, AccessibilityRules.propose(it))
        }
    }
    @Test fun ocrTransferAndRedPacketUseSuccessGeometryAndIgnoreMarketing() {
        fun line(text: String, top: Int, height: Int = 40) = OcrReceiptLine(text, 300, top, 700, top + height)
        val transfer = OcrReceiptRules.evaluate(listOf(line("支付成功", 200), line("待测试对象确认收款", 500), line("¥1.00", 700, 120)), 2200)
        assertEquals("RECOGNIZED", transfer.reason)
        assertEquals("转账给测试对象", transfer.merchant)
        val red = OcrReceiptRules.evaluate(listOf(line("红包已发出", 200), line("红包发给测试对象", 500), line("¥2.00", 700, 120)), 2200)
        assertEquals("RECOGNIZED", red.reason)
        assertEquals("红包发给测试对象", red.merchant)
        assertNull(OcrReceiptRules.evaluate(listOf(line("领取红包", 200), line("¥2.00", 700, 120)), 2200).receiptText)
    }
}
