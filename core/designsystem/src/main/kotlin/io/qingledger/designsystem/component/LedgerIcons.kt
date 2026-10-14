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
