package io.qingledger.domain.model

data class CaptureOverview(val todayCount: Int? = null, val latest: PendingCandidate? = null, val latestMerchant: String? = null, val zoneId: String = java.time.ZoneId.systemDefault().id)

object CaptureDisplayRules {
    fun status(method: CaptureMethod, enabled: Boolean, access: Boolean, connected: Boolean, sources: Int): String = when {
        method !in setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.NOTIFICATION, CaptureMethod.SHIZUKU) -> "尚未接入"
        !access -> "未授权"
        !enabled -> "已暂停"
        !connected -> "未连接"
        sources == 0 -> "未选择来源"
        else -> "运行正常"
    }
    fun matches(state: String, filter: Int): Boolean = when (filter) {
        1 -> state in setOf("AUTO_POSTED", "CONFIRMED")
        2 -> state in setOf("SKIPPED", "IGNORED")
        3 -> state == "PENDING"
        else -> true
    }
    fun stateLabel(state: String): String = when (state) {
        "AUTO_POSTED", "CONFIRMED" -> "已入账"
        "MERGED" -> "已关联"
        "SKIPPED", "IGNORED" -> "已跳过"
        "PENDING" -> "待确认"
        else -> "未知状态"
    }
}
