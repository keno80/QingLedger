package io.qingledger.app.automationui

fun sourceLabel(source: String): String = when (source) {
    "com.eg.android.AlipayGphone" -> "支付宝"
    "com.tencent.mm" -> "微信"
    "com.android.shell" -> "ADB 调试样例（非支付通知）"
    "io.qingledger.paymentsimulator" -> "支付测试器（模拟）"
    else -> source
}
fun reviewLabel(reason: String): String = when (reason) {
    "MISSING_AMOUNT" -> "金额缺失，请手工补充"
    "AMBIGUOUS_AMOUNT" -> "出现多个金额，请核对实付金额"
    "AMBIGUOUS_DIRECTION" -> "收支方向不明确，请主动选择"
    "TRANSFER_REVIEW" -> "可能是转账、充值或还款，请核对类型"
    "REFUND_REVIEW" -> "退款需通过原账单人工处理，当前不能直接入账"
    "UNSUPPORTED_CURRENCY" -> "币种不受支持，当前仅支持人民币"
    "POSSIBLE_DUPLICATE" -> "疑似重复，可关联原账单或明确作为新交易入账"
    "NOTIFICATION_UPDATE" -> "通知内容更新，请核对是否为同笔交易"
    else -> "来源模板未经验证，请核对后确认"
}
