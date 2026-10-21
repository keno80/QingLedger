package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class AccessibilityTreeReaderTest {
    private data class Node(override val sourcePackage: String? = "wechat", override val visible: Boolean = true,
        override val password: Boolean = false, override val text: String? = null, override val description: String? = null,
        override val selected: Boolean = false, val children: List<Node> = emptyList()) : AccessiblePageNode {
        override val childCount get() = children.size
        override fun child(index: Int): AccessiblePageNode = children[index]
    }
    @Test fun hiddenContainerDoesNotDiscardVisibleDescendants() {
        val root = Node(visible = false, text = "隐藏文字", children = listOf(Node(text = "支付成功"), Node(text = "¥1.23")))
        val result = AccessibilityTreeReader.read(root, "wechat")
        assertEquals("支付成功\n¥1.23", result.text)
        assertEquals(3, result.stats.visited)
        assertEquals(1, result.stats.hidden)
    }
    @Test fun unspecifiedChildPackageInheritsVerifiedRootButForeignSubtreeIsRejected() {
        val root = Node(children = listOf(Node(sourcePackage = null, text = "支付成功"), Node(text = "¥1.23"),
            Node(sourcePackage = "other", text = "不要读取", children = listOf(Node(text = "¥99.00")))))
        val result = AccessibilityTreeReader.read(root, "wechat")
        assertEquals("支付成功\n¥1.23", result.text)
        assertEquals(1, result.stats.unspecifiedPackage)
        assertEquals(1, result.stats.foreign)
    }
    @Test fun unverifiedRootAndPasswordSubtreesNeverExposeContent() {
        assertTrue(AccessibilityTreeReader.read(Node(sourcePackage = null, text = "不可读取"), "wechat").text.isEmpty())
        val result = AccessibilityTreeReader.read(Node(children = listOf(Node(password = true, text = "secret", children = listOf(Node(text = "hidden"))), Node(text = "完成"))), "wechat")
        assertEquals("完成", result.text)
        assertEquals(1, result.stats.passwords)
    }
    @Test fun traversalIsBoundedAndReportsTruncation() {
        val result = AccessibilityTreeReader.read(Node(children = (1..100).map { Node(text = "$it") }), "wechat", maxNodes = 10)
        assertEquals(10, result.stats.visited)
        assertTrue(result.stats.truncated)
        assertTrue(result.text.lines().size <= 9)
    }
}
