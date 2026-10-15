package io.qingledger.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.theme.LedgerColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppBottomBar(route: AppRoute, onSelect: (AppRoute) -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Constrain the native component's outer full-width box, not its inner modifier.
        Box(Modifier.width(204.dp)) {
            FloatingNavigationBar(horizontalOutSidePadding = 0.dp, defaultWindowInsetsPadding = false, color = MiuixTheme.colorScheme.surface) {
                listOf(AppRoute.LEDGER, AppRoute.STATISTICS, AppRoute.SETTINGS).forEach { item ->
                    FloatingNavigationBarItem(
                        selected = route == item,
                        onClick = { onSelect(item) },
                        icon = when (item) {
                            AppRoute.LEDGER -> LedgerIcons.Ledger
                            AppRoute.STATISTICS -> LedgerIcons.Statistics
                            else -> LedgerIcons.User
                        },
                        label = item.title,
                        colors = NavigationBarDefaults.navigationBarItemColors(selectedContentColor = LedgerColors.accentContent),
                    )
                }
            }
        }
        // Miuix 0.9.4 always reserves 36.dp below its floating bar. Match the sibling's baseline.
        if (route == AppRoute.LEDGER) Box(Modifier.padding(bottom = 36.dp)) {
            FloatingActionButton(onClick = onAdd, shape = CircleShape, minWidth = 56.dp, minHeight = 56.dp) {
                Icon(LedgerIcons.Add, "记一笔", tint = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
            }
        }
    }
}
