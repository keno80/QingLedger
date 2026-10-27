package io.qingledger.domain.model

data class ShizukuWindow(
    val token: String,
    val sourcePackage: String,
    val activity: String,
    val displayId: Int,
    val secure: Boolean,
)

data class ShizukuFrameLease(val window: ShizukuWindow, val generation: Long, val epoch: Long)

/** Fails closed whenever the platform cannot prove the focused window identity. */
object ShizukuCaptureRules {
    fun isCurrent(
        lease: ShizukuFrameLease,
        currentWindow: ShizukuWindow?,
        sources: Set<String>,
        ownPackage: String,
        currentGeneration: Long?,
        currentEpoch: Long,
        interactive: Boolean,
        unlocked: Boolean,
        authorized: Boolean,
        connected: Boolean,
    ): Boolean = authorized && connected && lease.epoch == currentEpoch &&
        currentGeneration != null && currentWindow != null &&
        acceptFrame(lease.window, currentWindow, sources, ownPackage, lease.generation,
            currentGeneration, interactive, unlocked)

    fun parseFocusedWindow(rawDump: String): ShizukuWindow? {
        // A full window dump starts with the last ANR's stale focus/windows. The current
        // policy/display/window sections follow this header under WindowManager's lock.
        val currentHeader = Regex("(?m)^WINDOW MANAGER POLICY STATE \\(dumpsys window policy\\)\\s*$")
            .findAll(rawDump).lastOrNull()
        val dump = if (currentHeader == null) rawDump else rawDump.substring(currentHeader.range.last + 1)
        val focusMatches = Regex("(?m)^\\s*mCurrentFocus=Window\\{([a-fA-F0-9]+) u\\d+ ([^}\\s]+)}")
            .findAll(dump).map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()
        if (focusMatches.isEmpty()) return null
        val topIds = Regex("\\bmTopFocusedDisplayId=(\\d+)").findAll(dump)
            .mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()
        if (topIds.size > 1) return null
        val topDisplay = topIds.singleOrNull()
        val headers = Regex("(?m)^\\s*Window #\\d+ Window\\{([a-fA-F0-9]+) u\\d+ ([^}\\s]+)}:")
            .findAll(dump).toList()
        val candidates = focusMatches.mapNotNull { (token, component) ->
            val matching = headers.withIndex().filter { it.value.groupValues[1] == token && it.value.groupValues[2] == component }
            if (matching.size != 1) return@mapNotNull null
            val (index, header) = matching.single()
            val section = dump.substring(header.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: dump.length)
            val display = Regex("(?m)^\\s*mDisplayId=(\\d+)").find(section)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (topDisplay != null && display != topDisplay) return@mapNotNull null
            val sourcePackage = component.substringBefore('/', missingDelimiterValue = "")
            val activity = component.substringAfter('/', missingDelimiterValue = "")
            if (sourcePackage.isBlank() || activity.isBlank()) return@mapNotNull null
            // Window titles are app controlled. Attribute ownership is the authority for source identity.
            val owner = Regex("(?m)^\\s*mOwnerUid=\\d+[^\\r\\n]*\\bpackage=([a-zA-Z0-9_.]+)(?:\\s|$)")
                .find(section)?.groupValues?.get(1) ?: return@mapNotNull null
            if (owner != sourcePackage) return@mapNotNull null
            if (!Regex("\\bmViewVisibility=0x0\\b").containsMatchIn(section)) return@mapNotNull null
            val attrs = section.substringAfter("mAttrs=", missingDelimiterValue = "")
                .substringBefore("mViewVisibility=")
            val flags = Regex("(?:^|\\s)fl=([^\\r\\n}]*)").find(attrs)?.groupValues?.get(1)
                ?.substringBefore(" pfl=")?.trim() ?: return@mapNotNull null
            if (flags.isEmpty()) return@mapNotNull null
            val numeric = flags.removePrefix("0x").toLongOrNull(16)
            val secure = if (numeric != null) (numeric and 0x2000L) != 0L else {
                if (!flags.matches(Regex("[A-Z_0-9 |]+"))) return@mapNotNull null
                Regex("\\b(?:FLAG_)?SECURE\\b").containsMatchIn(flags)
            }
            ShizukuWindow(token, sourcePackage, activity, display, secure)
        }
        if (topDisplay == null && focusMatches.size != 1) return null
        return candidates.singleOrNull()
    }

    fun acceptFrame(
        before: ShizukuWindow,
        after: ShizukuWindow,
        sources: Set<String>,
        ownPackage: String,
        startedGeneration: Long,
        currentGeneration: Long,
        interactive: Boolean,
        unlocked: Boolean,
    ): Boolean = before == after && !before.secure && before.sourcePackage in sources &&
        before.sourcePackage != ownPackage && before.displayId >= 0 && before.token.isNotBlank() &&
        startedGeneration == currentGeneration && interactive && unlocked

    fun physicalDisplayId(logicalId: Int, mappings: Map<Int, Long>, physicalIds: Set<Long>): Long? {
        val physical = mappings[logicalId] ?: return null
        return physical.takeIf { it in physicalIds && mappings.values.count { candidate -> candidate == physical } == 1 }
    }

    fun parsePhysicalDisplayIds(dump: String): Set<Long> = Regex("(?m)^\\s*Display (\\d+) \\(HWC display \\d+\\):")
        .findAll(dump).mapNotNull { it.groupValues[1].toULongOrNull()?.toLong() }.toSet()
}
