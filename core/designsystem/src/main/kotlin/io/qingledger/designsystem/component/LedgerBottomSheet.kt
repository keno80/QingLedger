package io.qingledger.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** Content spacing is additional to the system/IME insets managed by miuix. */
@Composable
fun LedgerBottomSheet(show: Boolean, title: String, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    OverlayBottomSheet(show = show, title = title, onDismissRequest = onDismissRequest, defaultWindowInsetsPadding = true) {
        Box(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            content()
        }
    }
}
