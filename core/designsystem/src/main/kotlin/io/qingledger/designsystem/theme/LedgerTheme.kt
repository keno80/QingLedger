package io.qingledger.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private val LocalLedgerDark = staticCompositionLocalOf { false }

object LedgerColors {
    val expense: Color @Composable get() = if (LocalLedgerDark.current) Color(0xFFEF9298) else Color(0xFFD85E66)
    val income: Color @Composable get() = if (LocalLedgerDark.current) Color(0xFF6FD0B3) else Color(0xFF218B72)
    val accentContent: Color @Composable get() = if (LocalLedgerDark.current) Color(0xFF9DEBD5) else Color(0xFF155E4C)
    val accentSurface: Color @Composable get() = if (LocalLedgerDark.current) Color(0xFF243E36) else Color(0xFFE2F6EF)
}

@Composable
fun LedgerTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLedgerDark provides darkTheme) {
        MiuixTheme(
            colors = if (darkTheme) darkColorScheme(background = Color(0xFF151518), primary = Color(0xFF78D9BF), onPrimary = Color(0xFF155E4C))
            else lightColorScheme(background = Color(0xFFF5F5F7), primary = Color(0xFF74D8BD), onPrimary = Color(0xFF155E4C)),
            content = content,
        )
    }
}
