package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class ShizukuFrameValidityTest {
    private val window = ShizukuWindow("abc", "com.tencent.mm", "com.tencent.mm.Wallet", 0, false)
    private val lease = ShizukuFrameLease(window, generation = 7, epoch = 2)

    @Test fun acceptsOnlyLiveFrameWhoseWindowIsStillCurrentAfterOcr() {
        assertTrue(current())
    }

    @Test fun rejectsWindowSwitchWithoutAConfigurationChange() {
        assertFalse(current(after = window.copy(token = "def")))
        assertFalse(current(after = window.copy(activity = "com.tencent.mm.Home")))
        assertFalse(current(after = window.copy(displayId = 1)))
        assertFalse(current(after = null))
    }

    @Test fun rejectsStopOrReconnectEvenWhenSettingsGenerationDidNotChange() {
        assertFalse(current(epoch = 3))
        assertFalse(current(generation = null))
        assertFalse(current(generation = 8))
        assertFalse(current(connected = false))
    }

    @Test fun rejectsPermissionRevocationScreenLockAndSecureWindowAfterOcr() {
        assertFalse(current(authorized = false))
        assertFalse(current(interactive = false))
        assertFalse(current(unlocked = false))
        assertFalse(current(after = window.copy(secure = true)))
        assertFalse(current(sources = emptySet()))
    }

    private fun current(
        after: ShizukuWindow? = window,
        sources: Set<String> = setOf("com.tencent.mm"),
        epoch: Long = 2,
        generation: Long? = 7,
        interactive: Boolean = true,
        unlocked: Boolean = true,
        authorized: Boolean = true,
        connected: Boolean = true,
    ) = ShizukuCaptureRules.isCurrent(lease, after, sources, "io.qingledger.app", generation,
        epoch, interactive, unlocked, authorized, connected)
}
