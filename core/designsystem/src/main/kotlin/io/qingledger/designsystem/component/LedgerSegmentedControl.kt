package io.qingledger.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared miuix segmented appearance for both page-level and card-level selectors. */
@Composable
fun LedgerSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(4.dp)) {
        labels.forEachIndexed { index, label ->
            val active = selectedIndex == index
            Button(onClick = { onSelect(index) }, enabled = enabled,
                modifier = Modifier.weight(1f).height(32.dp).semantics { selected = active }, minWidth = 0.dp, minHeight = 32.dp,
                cornerRadius = 10.dp, insideMargin = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(color = if (active) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent)) {
                Text(label, fontSize = 12.sp, color = if (active) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
