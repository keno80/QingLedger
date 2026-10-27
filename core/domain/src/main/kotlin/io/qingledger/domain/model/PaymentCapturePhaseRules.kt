package io.qingledger.domain.model

object PaymentCapturePhaseRules {
    fun isTransientSurface(width: Int, height: Int) = width in 1..480 && height in 1..480
    fun isAuthenticationActivity(activity: String?) = activity != null && Regex("CheckPwd|CheckPassword|Fingerprint|FaceVerify|VerifyPassword").containsMatchIn(activity)
    fun isFailure(text: String) = text.lines().any { Regex("^(?:本次)?(?:(?:支付|付款|交易)已取消|已取消|付款失败|支付失败|交易失败)[，。！!]?.*$").matches(it.trim()) }
    fun isAuthentication(text: String) = Regex("请输入支付密码|验证指纹|指纹验证|面容验证|验证面容|正在验证|请验证身份|密码错误").containsMatchIn(text)
    fun nodeConfirmation(text: String): OcrPaymentContext? {
        if (text.contains("选择付款方式") || isFailure(text)) return null
        val lines = text.take(8192).lines().map(String::trim)
        val field = lines.indexOfFirst { it == "付款方式" || it == "付款方式：" }
        if (field < 0 || lines.none { it in setOf("确认付款", "确认支付", "支付", "使用密码") }) return null
        val before = lines.take(field).filterNot { Regex("原价|优惠|余额|剩余").containsMatchIn(it) }.joinToString("\n")
        val amounts = Regex("[¥￥]\\s*([0-9]{1,12}(?:\\.[0-9]{1,2})?)").findAll(before).mapNotNull { match -> LedgerRules.parseAmount(match.groupValues[1]) }.distinct().toList()
        return amounts.singleOrNull()?.let { OcrPaymentContext(it, null, null) }
    }
}
