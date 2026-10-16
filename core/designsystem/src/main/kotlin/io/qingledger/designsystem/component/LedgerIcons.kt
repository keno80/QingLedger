package io.qingledger.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original vector drawings; no image assets copied from the reference project. */
object LedgerIcons {
    fun forCategory(name: String): ImageVector = when (name) {
        "餐饮", "美食", "饮食" -> Dining
        "交通" -> Transport
        "购物" -> Shopping
        "居住", "住房" -> Housing
        "娱乐" -> Entertainment
        "医疗", "健康" -> Health
        else -> Other
    }
    val Dining = icon("Dining") { moveTo(5f, 3f); lineTo(5f, 10f); curveTo(5f, 14f, 11f, 14f, 11f, 10f); lineTo(11f, 3f); moveTo(8f, 3f); lineTo(8f, 21f); moveTo(18f, 3f); curveTo(14f, 6f, 14f, 13f, 18f, 13f); lineTo(18f, 21f); moveTo(18f, 3f); lineTo(18f, 13f) }
    val Transport = icon("Transport") { moveTo(6f, 4f); curveTo(6f, 1f, 18f, 1f, 18f, 4f); lineTo(18f, 16f); curveTo(18f, 20f, 6f, 20f, 6f, 16f); close(); moveTo(9f, 5f); lineTo(15f, 5f); lineTo(15f, 10f); lineTo(9f, 10f); close(); moveTo(9f, 15f); lineTo(9.2f, 15f); moveTo(15f, 15f); lineTo(15.2f, 15f); moveTo(9f, 19f); lineTo(6f, 22f); moveTo(15f, 19f); lineTo(18f, 22f) }
    val Shopping = icon("Shopping") { moveTo(5f, 5f); lineTo(19f, 5f); lineTo(20f, 21f); lineTo(4f, 21f); close(); moveTo(8f, 8f); curveTo(8f, 15f, 16f, 15f, 16f, 8f); moveTo(8f, 5f); curveTo(8f, 0f, 16f, 0f, 16f, 5f) }
    val Housing = icon("Housing") { moveTo(3f, 8f); lineTo(12f, 3f); lineTo(21f, 8f); close(); moveTo(5f, 11f); lineTo(5f, 18f); moveTo(10f, 11f); lineTo(10f, 18f); moveTo(14f, 11f); lineTo(14f, 18f); moveTo(19f, 11f); lineTo(19f, 18f); moveTo(3f, 21f); lineTo(21f, 21f) }
    val Entertainment = icon("Entertainment") { moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f) }
    val Health = icon("Health") { moveTo(12f, 2f); lineTo(20f, 6f); lineTo(19f, 15f); curveTo(19f, 19f, 12f, 22f, 12f, 22f); curveTo(12f, 22f, 5f, 19f, 5f, 15f); lineTo(4f, 6f); close(); moveTo(12f, 7f); lineTo(12f, 15f); moveTo(8f, 11f); lineTo(16f, 11f) }
    val Other = icon("Other") { moveTo(3f, 4f); lineTo(13f, 4f); lineTo(22f, 13f); lineTo(13f, 22f); lineTo(3f, 12f); close(); moveTo(7f, 8f); lineTo(7.2f, 8f); moveTo(11f, 9f); lineTo(17f, 15f) }
    val Add = icon("Add") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Wallet = icon("Wallet") { moveTo(19f, 7f); lineTo(5f, 7f); curveTo(2f, 7f, 2f, 3f, 5f, 3f); lineTo(18f, 3f); lineTo(18f, 7f); moveTo(3f, 5f); lineTo(3f, 19f); lineTo(21f, 19f); lineTo(21f, 7f); lineTo(19f, 7f); moveTo(21f, 11f); lineTo(15f, 11f); lineTo(15f, 15f); lineTo(21f, 15f) }
    val Database = icon("Database") { moveTo(4f, 5f); curveTo(4f, 1f, 20f, 1f, 20f, 5f); curveTo(20f, 9f, 4f, 9f, 4f, 5f); lineTo(4f, 19f); curveTo(4f, 23f, 20f, 23f, 20f, 19f); lineTo(20f, 5f); moveTo(4f, 12f); curveTo(4f, 16f, 20f, 16f, 20f, 12f) }
    val Gear = icon("Gear") {
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(16f, 6f); lineTo(19f, 6f); lineTo(22f, 11f); lineTo(20f, 14f); lineTo(20f, 17f); lineTo(15f, 21f); lineTo(12f, 19f); lineTo(9f, 21f); lineTo(4f, 18f); lineTo(4f, 14f); lineTo(2f, 11f); lineTo(5f, 6f); lineTo(8f, 6f); close()
        moveTo(16f, 12f); curveTo(16f, 17.3f, 8f, 17.3f, 8f, 12f); curveTo(8f, 6.7f, 16f, 6.7f, 16f, 12f)
    }
    val Back = icon("Back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }
    val Next = icon("Next") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    val Search = icon("Search") { moveTo(16f, 16f); lineTo(21f, 21f); moveTo(11f, 3f); curveTo(21f, 3f, 21f, 18f, 11f, 18f); curveTo(1f, 18f, 1f, 3f, 11f, 3f) }
    val Filter = icon("Filter") { moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 17f); lineTo(20f, 17f); moveTo(9f, 4f); lineTo(9f, 10f); moveTo(15f, 14f); lineTo(15f, 20f) }
    val User = icon("User") { moveTo(8f, 8f); curveTo(8f, 2f, 16f, 2f, 16f, 8f); curveTo(16f, 14f, 8f, 14f, 8f, 8f); moveTo(4f, 21f); curveTo(4f, 11f, 20f, 11f, 20f, 21f) }
    val Ledger = icon("Ledger") {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(5f, 21f); close()
        moveTo(9f, 8f); lineTo(15f, 8f)
        moveTo(9f, 12f); lineTo(15f, 12f)
        moveTo(9f, 16f); lineTo(13f, 16f)
    }
    val Statistics = icon("Statistics") {
        moveTo(4f, 4f); lineTo(4f, 20f); lineTo(21f, 20f)
        moveTo(8f, 16f); lineTo(8f, 12f)
        moveTo(13f, 16f); lineTo(13f, 7f)
        moveTo(18f, 16f); lineTo(18f, 10f)
    }
    val Automation = icon("Automation") {
        moveTo(13f, 2f); lineTo(5f, 14f); lineTo(11f, 14f)
        lineTo(10f, 22f); lineTo(19f, 10f); lineTo(13f, 10f); close()
    }
    val Settings = icon("Settings") {
        moveTo(4f, 6f); lineTo(20f, 6f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 18f); lineTo(20f, 18f)
        moveTo(9f, 3f); lineTo(9f, 9f)
        moveTo(15f, 9f); lineTo(15f, 15f)
        moveTo(9f, 15f); lineTo(9f, 21f)
    }

    private fun icon(name: String, drawing: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = drawing,
            )
        }.build()
}
