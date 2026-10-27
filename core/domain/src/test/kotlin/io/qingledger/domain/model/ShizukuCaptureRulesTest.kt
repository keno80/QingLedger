package io.qingledger.domain.model

import org.junit.Assert.*
import org.junit.Test

class ShizukuCaptureRulesTest {
    private val window = ShizukuWindow("abc", "com.tencent.mm", ".Wallet", 0, false)

    @Test fun parsesOnlyFocusedWindowIncludingItsOwnDisplayAndFlags() {
        assertEquals(window, ShizukuCaptureRules.parseFocusedWindow(dump()))
    }

    @Test fun rejectsMissingFlagsOrWindowIdentity() {
        assertNull(ShizukuCaptureRules.parseFocusedWindow(dump().replace("fl=81810100", "")))
        assertNull(ShizukuCaptureRules.parseFocusedWindow(dump().replace("mCurrentFocus=Window{abc", "mCurrentFocus=Window{xyz")))
    }

    @Test fun readsSecureHexAndSymbolicFlags() {
        assertTrue(ShizukuCaptureRules.parseFocusedWindow(dump().replace("81810100", "81812100"))!!.secure)
        assertTrue(ShizukuCaptureRules.parseFocusedWindow(dump().replace("81810100", "SECURE HARDWARE_ACCELERATED"))!!.secure)
    }

    @Test fun rejectsSpoofedWindowTitleAndConflictingDisplayIdentity() {
        assertNull(ShizukuCaptureRules.parseFocusedWindow(dump().replace("package=com.tencent.mm", "package=com.example.other")))
        assertNull(ShizukuCaptureRules.parseFocusedWindow(dump().replace("mDisplayId=0 taskId=42", "mDisplayId=1 taskId=42") + "\nmTopFocusedDisplayId=0"))
    }

    @Test fun usesTopFocusedPhysicalDisplayAndIgnoresOtherDisplayFocus() {
        val displays = """
            Display: mDisplayId=3
              mCurrentFocus=Window{def u0 io.qingledger/.MainActivity}
              mFocusedApp=ActivityRecord{ddd u0 io.qingledger/.MainActivity t99}
            Display: mDisplayId=0
              mCurrentFocus=Window{abc u0 com.tencent.mm/.Wallet}
              mFocusedApp=ActivityRecord{bbb u0 com.tencent.mm/.Wallet t42}
            mTopFocusedDisplayId=0
        """.trimIndent()
        assertEquals(window, ShizukuCaptureRules.parseFocusedWindow(dump().substringBefore("  mCurrentFocus") + displays))
    }

    @Test fun rejectsAmbiguousActiveDisplaysWithoutTopFocusProof() {
        assertNull(ShizukuCaptureRules.parseFocusedWindow(dump() + "\nmCurrentFocus=Window{def u0 io.qingledger/.MainActivity}"))
    }

    @Test fun neverUsesHistoricalAnrWindowMetadata() {
        val stale = dump().replace("abc", "999").replace(".Wallet", ".OldWallet")
        val fullDump = "WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n$stale\n" +
            "WINDOW MANAGER POLICY STATE (dumpsys window policy)\n" + dump()
        assertEquals(window, ShizukuCaptureRules.parseFocusedWindow(fullDump))
    }

    @Test fun parsesConnectedXiaomiOwnAppWindowWithInactiveSecondaryDisplays() {
        // Only own-app metadata was read over ADB; no screenshot or payment UI was opened.
        val sample = """
            WINDOW MANAGER POLICY STATE (dumpsys window policy)
              mCurrentFocus=null
              mCurrentFocus=null
              mCurrentFocus=Window{cdfa54c u0 io.qingledger.app/io.qingledger.app.MainActivity}
              mTopFocusedDisplayId=0
              Window #12 Window{cdfa54c u0 io.qingledger.app/io.qingledger.app.MainActivity}:
                mDisplayId=0 taskId=18037
                mOwnerUid=10236 showForAllUsers=false package=io.qingledger.app appop=NONE
                mAttrs={(0,0)(fillxfill) sim={adjust=pan forwardNavigation} layoutInDisplayCutoutMode=always pkg = io.qingledger.app adpkg = null ty=BASE_APPLICATION wanim=0x103030d
                  fl=LAYOUT_IN_SCREEN LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED DRAWS_SYSTEM_BAR_BACKGROUNDS}
                mViewVisibility=0x0 mHaveFrame=true mObscured=false
        """.trimIndent()
        assertEquals(ShizukuWindow("cdfa54c", "io.qingledger.app", "io.qingledger.app.MainActivity", 0, false), ShizukuCaptureRules.parseFocusedWindow(sample))
    }

    @Test fun acceptsStableSelectedUnlockedWindow() {
        assertTrue(accept())
    }

    @Test fun discardsWindowSwitchOverlaySecureLockedAndStaleGeneration() {
        assertFalse(accept(after = window.copy(token = "other")))
        assertFalse(accept(after = window.copy(displayId = 1)))
        assertFalse(accept(before = window.copy(sourcePackage = "io.qingledger")))
        val protectedWindow = window.copy(secure = true)
        assertFalse(accept(before = protectedWindow, after = protectedWindow))
        assertFalse(accept(interactive = false))
        assertFalse(accept(unlocked = false))
        assertFalse(accept(generation = 8))
    }

    @Test fun neverAcceptsOwnOverlayEvenIfItWasAccidentallySelected() {
        val own = window.copy(sourcePackage = "io.qingledger")
        assertFalse(ShizukuCaptureRules.acceptFrame(own, own, setOf("io.qingledger"), "io.qingledger", 7, 7, true, true))
    }

    @Test fun mapsLogicalDisplayOnlyToUniqueProvenPhysicalId() {
        assertEquals(42L, ShizukuCaptureRules.physicalDisplayId(7, mapOf(7 to 42L, 0 to 2L), setOf(42L, 2L)))
        assertNull(ShizukuCaptureRules.physicalDisplayId(7, mapOf(0 to 42L), setOf(42L)))
        assertNull(ShizukuCaptureRules.physicalDisplayId(7, mapOf(7 to 42L), setOf(2L)))
        assertNull(ShizukuCaptureRules.physicalDisplayId(7, mapOf(7 to 42L, 0 to 42L), setOf(42L)))
    }

    @Test fun readsUnsignedPhysicalDisplayIdsFromSurfaceFlinger() {
        assertEquals(setOf(4619827677550801152L, -1L), ShizukuCaptureRules.parsePhysicalDisplayIds("""
            Display 4619827677550801152 (HWC display 0): port=0 pnpId=ABC displayName=Phone
            Display 18446744073709551615 (HWC display 1): port=1 pnpId=DEF displayName=HDMI
        """.trimIndent()))
        assertTrue(ShizukuCaptureRules.parsePhysicalDisplayIds("Permission Denial: can't dump SurfaceFlinger").isEmpty())
    }

    private fun accept(
        before: ShizukuWindow = window,
        after: ShizukuWindow = window,
        generation: Long = 7,
        interactive: Boolean = true,
        unlocked: Boolean = true,
    ) = ShizukuCaptureRules.acceptFrame(before, after, setOf("com.tencent.mm"), "io.qingledger", 7, generation, interactive, unlocked)

    private fun dump() = """
        WINDOW MANAGER WINDOWS (dumpsys window windows)
          Window #0 Window{def u0 io.qingledger/.MainActivity}:
            mDisplayId=3 taskId=99
            mOwnerUid=10001 showForAllUsers=false package=io.qingledger appop=NONE
            mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION fl=81812100}
          Window #1 Window{abc u0 com.tencent.mm/.Wallet}:
            mDisplayId=0 taskId=42
            mOwnerUid=10002 showForAllUsers=false package=com.tencent.mm appop=NONE
            mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION
              fl=81810100
              pfl=USE_BLAST}
            mViewVisibility=0x0 mHaveFrame=true
          mCurrentFocus=Window{abc u0 com.tencent.mm/.Wallet}
          mFocusedApp=ActivityRecord{bbb u0 com.tencent.mm/.Wallet t42}
    """.trimIndent()
}
