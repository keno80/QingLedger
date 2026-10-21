package io.qingledger.domain.model

data class AccessibleWindowReading(val windowId: Int, val snapshot: AccessiblePageSnapshot)
data class AccessibleWindowDecision(val reading: AccessibleWindowReading?, val decision: AccessibilityRules.Decision)
object AccessibilityWindowRules {
    fun choose(readings: List<AccessibleWindowReading>): AccessibleWindowDecision {
        val evaluated = readings.map { it to AccessibilityRules.evaluate(it.snapshot.text) }
        val recognized = evaluated.filter { it.second.proposal != null }
        if (recognized.map { it.second.proposal!!.let { p -> p.kind to p.amountMinor } }.distinct().size > 1)
            return AccessibleWindowDecision(null, AccessibilityRules.Decision(null, "WINDOW_CONFLICT"))
        val chosen = recognized.firstOrNull() ?: evaluated.firstOrNull { it.first.snapshot.text.isNotBlank() } ?: evaluated.firstOrNull()
        return AccessibleWindowDecision(chosen?.first, chosen?.second ?: AccessibilityRules.Decision(null, "ROOT_UNAVAILABLE"))
    }
}
