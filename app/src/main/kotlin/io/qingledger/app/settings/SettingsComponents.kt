package io.qingledger.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.theme.LedgerColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 3.dp), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
internal fun SettingsNavigationRow(title: String, summary: String?, icon: ImageVector?, onClick: () -> Unit, end: (@Composable RowScope.() -> Unit)? = null) {
    BasicComponent(modifier = Modifier.fillMaxWidth().heightIn(min = 65.dp), insideMargin = PaddingValues(horizontal = 16.dp, vertical = 13.dp), onClick = onClick,
        startAction = if (icon == null) null else ({
            Box(Modifier.padding(end = 12.dp).size(34.dp).background(LedgerColors.accentSurface, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = LedgerColors.accentContent)
            }
        }),
        endActions = end ?: { Icon(LedgerIcons.Next, null, modifier = Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
    ) {
        Text(title, fontSize = 14.sp)
        if (!summary.isNullOrEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(summary, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun SettingsRowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp).height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)))
}
