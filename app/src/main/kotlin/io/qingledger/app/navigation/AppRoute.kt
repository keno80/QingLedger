package io.qingledger.app.navigation

enum class AppRoute(val title: String, val subtitle: String) {
    LEDGER("账本", "每一笔，都有迹可循"),
    STATISTICS("统计", "看清钱花在哪里"),
    AUTOMATION("自动", "按需授权，轻松记账"),
    SETTINGS("我的", "让记账更合你的习惯");

    companion object {
        fun fromSavedValue(value: String): AppRoute = entries.firstOrNull { it.name == value } ?: LEDGER
    }
}
