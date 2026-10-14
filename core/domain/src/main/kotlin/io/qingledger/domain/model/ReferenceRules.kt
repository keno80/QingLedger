package io.qingledger.domain.model

object ReferenceRules {
    fun normalizeName(text: String): String? {
        if (text.any { it.isISOControl() }) return null
        return text.trim().takeIf { it.isNotBlank() && it.length <= 32 }
    }
}

data class TransactionChange(val revision: Int, val operation: String, val occurredAt: Long)
