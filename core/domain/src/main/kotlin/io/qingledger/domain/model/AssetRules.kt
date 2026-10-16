package io.qingledger.domain.model

import java.math.BigDecimal

data class AccountBalance(val account: Account, val movementMinor: Long) {
    val balanceMinor: Long? get() = account.openingBalanceMinor?.let { Math.addExact(it, movementMinor) }
}

data class AssetSummary(val assetsMinor: Long = 0, val liabilitiesMinor: Long = 0, val configuredCount: Int = 0, val accountCount: Int = 0) {
    val netMinor: Long get() = Math.subtractExact(assetsMinor, liabilitiesMinor)
}

object AssetRules {
    fun parseOpeningBalance(text: String): Long? {
        val value = text.trim()
        if (!Regex("-?[0-9]+(?:\\.[0-9]{1,2})?").matches(value)) return null
        return try { BigDecimal(value).movePointRight(2).longValueExact().takeIf { it in -LedgerRules.MAX_AMOUNT..LedgerRules.MAX_AMOUNT } }
        catch (_: ArithmeticException) { null }
    }

    fun summarize(rows: List<AccountBalance>): AssetSummary {
        var assets = 0L
        var liabilities = 0L
        var configured = 0
        rows.forEach { row -> row.balanceMinor?.let { balance ->
            configured++
            if (balance >= 0) assets = Math.addExact(assets, balance)
            else liabilities = Math.addExact(liabilities, Math.negateExact(balance))
        } }
        return AssetSummary(assets, liabilities, configured, rows.size)
    }
}
