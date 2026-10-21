package io.qingledger.domain.model

interface AccessiblePageNode {
    val nodeType: String? get() = null
    val sensitive: Boolean get() = false
    val sourcePackage: String?
    val visible: Boolean
    val password: Boolean
    val text: String?
    val description: String?
    val selected: Boolean
    val childCount: Int
    fun child(index: Int): AccessiblePageNode?
}

data class PageReadStats(val visited: Int, val hidden: Int, val foreign: Int, val unspecifiedPackage: Int, val passwords: Int, val rootChildren: Int, val truncated: Boolean, val sensitive: Int = 0, val nodeTypes: List<String> = emptyList()) {
    fun summary() = "节点 $visited · 隐藏 $hidden · 异来源 $foreign · 未标来源 $unspecifiedPackage · 密码 $passwords · 敏感标记 $sensitive · 根子节点 $rootChildren" + (if (nodeTypes.isNotEmpty()) " · 类型 " + nodeTypes.joinToString(",") else "") + if (truncated) " · 已达读取上限" else ""
}
data class AccessiblePageSnapshot(val text: String, val selected: List<String>, val stats: PageReadStats)

object AccessibilityTreeReader {
    fun read(root: AccessiblePageNode, expectedPackage: String, maxNodes: Int = 256): AccessiblePageSnapshot {
        val stack = ArrayDeque<Pair<AccessiblePageNode, Int>>()
        val fields = linkedSetOf<String>()
        val selected = linkedSetOf<String>()
        var visited = 0; var hidden = 0; var foreign = 0; var unspecified = 0; var passwords = 0; var length = 0; var truncated = false
        var sensitive = 0
        val nodeTypes = linkedSetOf<String>()
        val rootChildren = root.childCount
        if (root.sourcePackage == expectedPackage) stack.add(root to 0) else foreign++
        while (stack.isNotEmpty() && visited < maxNodes && length < 8192) {
            val (node, depth) = stack.removeLast(); visited++
            if (node.password) { passwords++; continue }
            if (node.sourcePackage == null) unspecified++
            if (node.sourcePackage != null && node.sourcePackage != expectedPackage) { foreign++; continue }
            if (node.sensitive) sensitive++
            if (nodeTypes.size < 4) node.nodeType?.take(96)?.let(nodeTypes::add)
            if (!node.visible) hidden++
            else {
                if (node.selected) node.text?.take(512)?.let(selected::add)
                listOf(node.text, node.description).forEach { value ->
                    val item = value?.trim()?.take(512)
                    if (!item.isNullOrBlank() && fields.add(item)) length += item.length + 1
                }
            }
            if (depth < 20) {
                if (node.childCount > 32) truncated = true
                for (index in (minOf(node.childCount, 32) - 1) downTo 0) node.child(index)?.let { stack.add(it to depth + 1) }
            } else if (node.childCount > 0) truncated = true
        }
        return AccessiblePageSnapshot(fields.joinToString("\n").take(8192), selected.toList(), PageReadStats(visited, hidden, foreign, unspecified, passwords, rootChildren, truncated || stack.isNotEmpty(), sensitive, nodeTypes.toList()))
    }
}
