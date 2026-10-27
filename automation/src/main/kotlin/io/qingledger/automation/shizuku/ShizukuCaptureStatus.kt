package io.qingledger.automation.shizuku

enum class ShizukuConnectionState(val label: String) {
    NOT_INSTALLED("未安装"),
    NOT_RUNNING("未启动"),
    PERMISSION_REQUIRED("待授权"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    DISCONNECTED("连接断开"),
    UNSUPPORTED("能力不支持"),
}

data class ShizukuCaptureStatus(
    val state: ShizukuConnectionState,
    val detail: String? = null,
) {
    val label: String get() = state.label
}
