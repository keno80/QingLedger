package io.qingledger.automation.shizuku

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.qingledger.domain.model.ShizukuCaptureRules
import io.qingledger.domain.model.ShizukuWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android's ICU regex parser is stricter about unmatched braces than desktop Java. */
@RunWith(AndroidJUnit4::class)
class ShizukuWindowParserTest {
    @Test fun parsesWindowBracesOnAndroidRuntime() {
        assertEquals(ShizukuWindow("abc", "io.qingledger.app", ".MainActivity", 0, false),
            ShizukuCaptureRules.parseFocusedWindow(window("81810100")))
    }

    @Test fun stillRecognizesProtectedWindowOnAndroidRuntime() {
        assertEquals(ShizukuWindow("abc", "io.qingledger.app", ".MainActivity", 0, true),
            ShizukuCaptureRules.parseFocusedWindow(window("SECURE HARDWARE_ACCELERATED")))
    }

    private fun window(flags: String) = """
        WINDOW MANAGER POLICY STATE (dumpsys window policy)
          mCurrentFocus=Window{abc u0 io.qingledger.app/.MainActivity}
          mTopFocusedDisplayId=0
          Window #0 Window{abc u0 io.qingledger.app/.MainActivity}:
            mDisplayId=0 taskId=42
            mOwnerUid=10236 showForAllUsers=false package=io.qingledger.app appop=NONE
            mAttrs={(0,0)(fillxfill) fl=$flags}
            mViewVisibility=0x0 mHaveFrame=true
    """.trimIndent()
}
