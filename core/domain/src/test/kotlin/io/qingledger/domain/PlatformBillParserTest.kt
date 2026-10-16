package io.qingledger.domain

import io.qingledger.domain.importing.*
import io.qingledger.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class PlatformBillParserTest {
    private val ali = "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,\r\n"
    private val wx = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注\r\n"
    private fun parse(s: String, source: ImportSource = ImportSource.ALIPAY) = PlatformBillParser.parse(s.toByteArray(if (source == ImportSource.ALIPAY) charset("GB18030") else Charsets.UTF_8), source)
    @Test fun alipayGbkPreamblePaidWaitingGoodsAndDiscountAccountAreParsed() {
        val data = "导出信息：\r\n说明：本地测试\r\n" + ali + "2026-10-16 12:00:00,餐饮美食,测试商家,隐藏,午餐,支出,12.34,中国银行储蓄卡(1111)&优惠,等待确认收货,1234567890123456789012345678,shop1,,\r\n"
        val result = parse(data)
        assertTrue(result.issues.isEmpty()); assertEquals(1, result.totalRows)
        val row = result.records.single()
        assertEquals(1234L, row.amountMinor); assertEquals("中国银行储蓄卡（1111）", row.account)
        assertEquals("com.eg.android.AlipayGphone", row.sourcePackage)
        assertEquals(row.id, parse(data).records.single().id)
    }
    @Test fun alipayRefundAndOriginalClosedExpenseAreRetainedWhileNonCashRowsAreSkipped() {
        val data = ali + "2026-10-16 12:00:00,购物,商家,,原单,支出,100,余额宝,交易关闭,original,shop1,,\r\n" +
            "2026-10-16 13:00:00,退款,商家,,退款,不计收支,30,余额宝,退款成功,refund,shop1,,\r\n" +
            "2026-10-16 14:00:00,信用借还,银行,,还款,不计收支,100,,还款成功,repay,shop2,,\r\n" +
            "2026-10-16 15:00:00,购物,商家,,取消,不计收支,100,,交易关闭,cancel,shop3,,\r\n"
        val result = parse(data)
        assertEquals(2, result.records.size); assertEquals(2, result.skipped.size)
        val refund = result.records.single { it.kind == TransactionKind.REFUND }
        assertEquals(result.records.single { it.kind == TransactionKind.EXPENSE }.id, refund.originalExpenseId)
        assertEquals("购物", refund.category)
    }
    @Test fun wechatOriginalAmountIsNotReducedTwiceAndRefundMayRemainStandalone() {
        val data = wx + "2026-10-16 12:00:00,商户消费,商家,商品,支出,¥100.00,零钱,已退款(¥30.00),wx1,shop1,/\r\n" +
            "2026-10-16 13:00:00,商家-退款,商家,商品,收入,¥30.00,零钱,已退款¥30.00,wxrefund,shop1,/\r\n" +
            "2026-10-16 14:00:00,商家-退款,商家,商品,收入,¥1.00,/,已全额退款,standalone,/,/\r\n"
        val result = parse(data, ImportSource.WECHAT)
        assertEquals(10000L, result.records.first().amountMinor)
        assertEquals(2, result.records.count { it.kind == TransactionKind.REFUND })
        assertNull(result.records.last().originalExpenseId)
        assertEquals("微信零钱", result.records.first().account)
    }
    @Test fun badAmountsUnknownStatusesAndMissingIdsNeverBecomeReadyRows() {
        val data = wx + "2026-10-16 12:00:00,商户消费,商家,商品,支出,0.00,零钱,支付成功,wx1,/,/\r\n" +
            "2026-10-16 12:00:00,商户消费,商家,商品,支出,1.00,零钱,未知状态,wx2,/,/\r\n" +
            "2026-10-16 12:00:00,商户消费,商家,商品,支出,1.00,零钱,支付成功,/,/,/\r\n"
        val result = parse(data, ImportSource.WECHAT)
        assertTrue(result.records.isEmpty()); assertEquals(2, result.issues.size); assertEquals(1, result.skipped.size)
    }
    @Test fun nonNumericIdsAreNotMistakenForScientificNotationAndPlatformsHaveSeparateIds() {
        val data = ali + "2026-10-16 12:00:00,餐饮,商家,,商品,支出,10,现金,交易成功,id-expense-0721,shop,,\r\n"
        assertEquals(1, parse(data).records.size)
        assertEquals(1, parse(data.replace("id-expense-0721", "4.200000000001E+27")).issues.size)
        assertNotEquals(PlatformBillParser.stableId(ImportSource.ALIPAY,"id"), PlatformBillParser.stableId(ImportSource.WECHAT,"id"))
    }
    @Test fun multipleFundingAccountsAreRejectedInsteadOfAssigningWholeAmountToFirstAccount() {
        val data = ali + "2026-10-16 12:00:00,餐饮,商家,,商品,支出,10,余额宝&中国银行储蓄卡(1111),交易成功,mixed,shop,,\r\n"
        val parsed = parse(data)
        assertTrue(parsed.records.isEmpty()); assertTrue(parsed.issues.single().message.contains("组合付款"))
    }
}
