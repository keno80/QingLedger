package io.qingledger.domain.model

/** Uses monotonic elapsed time. Contains metadata only, never images or page text. */
class AccessibilityScreenshotGate(private val cooldownMs: Long = 30_000) {
    private val requests = linkedMapOf<Triple<String, Int, Int>, Long>()

    fun request(source: String, display: Int, window: Int, empty: Boolean, currentAndUnlocked: Boolean, now: Long): Boolean {
        if (!empty || !currentAndUnlocked || display < 0 || window < 0 || source.isBlank()) return false
        val key = Triple(source, display, window)
        val previous = requests[key]
        if (previous != null && now - previous < cooldownMs) return false
        requests.remove(key)
        requests[key] = now
        if (requests.size > 16) requests.remove(requests.keys.first())
        return true
    }
}
