package io.qingledger.domain.model

/** Notification copy uses committed state, never a parser's prediction of a booking. */
data class CaptureNotice(val title: String, val description: String) {
    companion object {
        fun fromState(state: String?): CaptureNotice = when (state) {
            "AUTO_POSTED", "CONFIRMED" -> CaptureNotice("识别完成 · 已入账", "账单已保存，可在轻账中修改或删除。")
            "MERGED" -> CaptureNotice("识别完成 · 已关联", "已关联原账单，未重复入账。")
            "PENDING" -> CaptureNotice("识别完成 · 待确认", "确认后才会入账，关闭确认窗口将舍弃本次识别。")
            "SKIPPED", "IGNORED" -> CaptureNotice("识别完成 · 已跳过", "未生成新账单，可打开轻账查看采集记录。")
            else -> CaptureNotice("识别未完成", "未确认账单保存结果，请打开轻账检查。")
        }
    }
}

/** Bounded process-local replay suppression; persisted events are checked separately. */
class CaptureNoticeGate(private val capacity: Int = 128) {
    private val seen = LinkedHashSet<String>()
    init { require(capacity > 0) }
    fun accept(key: String): Boolean {
        if (!seen.add(key)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
