package io.qingledger.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun AmountText(amountMinor: Long, modifier: Modifier = Modifier) {
    val amount = remember(amountMinor) {
        formatMoney(amountMinor)
    }
    Text(text = amount, modifier = modifier, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
}
