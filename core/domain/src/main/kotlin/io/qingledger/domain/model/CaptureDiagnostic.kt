package io.qingledger.domain.model

/** Metadata only. No page text, financial amount, merchant or account is retained. */
data class CaptureDiagnostic(val sourcePackage: String, val at: Long, val stage: String, val reason: String, val fields: Int? = null)

object CaptureDiagnosticLabels {
    fun stage(value: String): String = when (value) {
        "EVENT" -> "收到事件"; "READ" -> "页面读取"; "RULE" -> "规则判断"; "SAVE" -> "入账处理"; else -> "采集状态"
    }
    fun reason(value: String): String = when (value) {
        "WINDOW_EVENT" -> "收到页面变化事件"
        "NOTIFICATION_EVENT" -> "收到通知事件"
        "METHOD_INACTIVE" -> "当前未使用无障碍方式"
        "CAPTURE_DISABLED" -> "自动记账已暂停"
        "SOURCE_DISABLED" -> "该来源未开启"
        "PAGE_READ" -> "已读取可见页面文字"
        "PAGE_EMPTY" -> "页面未提供可读取文字"
        "ROOT_UNAVAILABLE" -> "无法获取当前页面节点"
        "SOURCE_MISMATCH" -> "当前窗口不属于事件来源"
        "READ_FAILED" -> "读取页面发生异常"
        "PAYMENT_IN_PROGRESS" -> "仍在确认付款或选择付款方式"
        "HISTORY_PAGE" -> "历史、列表或聊天记录不自动入账"
        "TRANSFER_REVIEW" -> "转账、充值、提现或还款暂不自动入账"
        "NO_SUCCESS_STATE" -> "未识别到明确交易成功状态"
        "MISSING_AMOUNT" -> "未识别到有效交易金额"
        "AMBIGUOUS_AMOUNT" -> "存在多个金额，无法确定交易金额"
        "AMBIGUOUS_DIRECTION" -> "收入、支出或退款方向冲突"
        "UNSUPPORTED_CURRENCY" -> "暂不支持该币种"
        "REFUND_REVIEW" -> "退款尚未明确到账"
        "UNSETTLED_OR_UNSUPPORTED" -> "交易未完成或文案暂未支持"
        "RECOGNIZED" -> "已识别成功状态与金额"
        "NOTICE_PATH_UNSUPPORTED" -> "该来源尚未接入通知识别，使用页面识别"
        "NOTICE_PAYLOAD_UNAVAILABLE" -> "通知事件未提供可读取的通知对象"
        "NOTICE_TEMPLATE_UNSUPPORTED" -> "通知文案暂未支持"
        "ALIPAY_NOTICE_REQUIRED" -> "支付宝等待到账通知入账"
        "QUEUED" -> "已进入入账队列"
        "SETTINGS_CHANGED" -> "采集配置已变化，事件作废"
        "DUPLICATE" -> "重复事件，未新增账单"
        "AUTO_POSTED", "CONFIRMED" -> "已入账"
        "MERGED" -> "已关联原账单，未重复入账"
        "SKIPPED" -> "事件已跳过，未生成账单"
        "QUEUE_FULL" -> "采集队列已满"
        "SAVE_FAILED" -> "保存失败，请检查账本状态"
        "EVENT_REJECTED" -> "事件已失效或被拒绝"
        else -> "未完成识别"
    }
}
