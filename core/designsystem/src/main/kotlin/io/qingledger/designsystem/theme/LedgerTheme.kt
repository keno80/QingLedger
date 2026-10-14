package io.qingledger.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun LedgerTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MiuixTheme(
        colors = if (darkTheme) darkColorScheme(background = Color(0xFF141414)) else lightColorScheme(background = Color(0xFFF5F5F5)),
        content = content,
    )
}
