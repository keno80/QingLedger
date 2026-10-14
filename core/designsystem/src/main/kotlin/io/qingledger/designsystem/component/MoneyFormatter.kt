package io.qingledger.designsystem.component

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

fun formatMoney(amountMinor: Long): String = NumberFormat.getCurrencyInstance(Locale.CHINA).format(BigDecimal.valueOf(amountMinor, 2))
