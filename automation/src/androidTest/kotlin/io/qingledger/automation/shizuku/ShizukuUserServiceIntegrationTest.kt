package io.qingledger.automation.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** Opt-in device checks: skips without the user's existing Shizuku grant; never grants it. */
@RunWith(AndroidJUnit4::class)
class ShizukuUserServiceIntegrationTest {
    @Test fun grantedUserServiceProbesRealWindowAndRejectsInvalidCaptureIdentity() = withGrantedService { service ->
        val probe = service.probe()
        assertEquals(probe.getString("detail"), ShizukuCaptureProtocol.OK, probe.getString("status"))
        assertNotNull(ShizukuCaptureProtocol.window(probe))
        val invalid = service.capture("not-a-window-token", 0)
        assertEquals(ShizukuCaptureProtocol.SKIP, invalid.metadata.getString("status"))
        assertNull(invalid.image)
    }

    @Test fun capturesOnlyWhenUserHasPlacedWechatInForeground() = withGrantedService { service ->
        val before = service.probe()
        val window = ShizukuCaptureProtocol.window(before)
        assumeTrue("需要用户手动打开未受保护的微信页面，测试不会点击或支付", window != null && window.sourcePackage == "com.tencent.mm" && !window.secure)
        val result = service.capture(window!!.token, window.displayId)
        assertEquals(result.metadata.getString("detail"), ShizukuCaptureProtocol.OK, result.metadata.getString("status"))
        val image = result.image
        assertNotNull(image)
        val bitmap = ShizukuImageTransport.readBitmap(image!!)
        try { assertFalse("系统返回受保护的黑屏", ShizukuImageTransport.isBlank(bitmap)) }
        finally { bitmap.recycle() }
    }

    private fun withGrantedService(check: (IReadOnlyCaptureService) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        // The normal app process must have received the Provider binder before this test starts.
        assumeTrue("需要已启动且由用户授权的 Shizuku", Shizuku.pingBinder())
        assumeTrue("需要既有 Shizuku 授权", runCatching {
            IShizukuService.Stub.asInterface(Shizuku.getBinder()).checkSelfPermission()
        }.getOrDefault(false))
        val latch = CountDownLatch(1)
        var remote: IReadOnlyCaptureService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = IReadOnlyCaptureService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { remote = null }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(app, ReadOnlyCaptureUserService::class.java))
            .tag("qingledger-read-only-capture-device-test").processNameSuffix("readonly_capture_test").daemon(false).version(3)
        try {
            Shizuku.bindUserService(args, connection)
            assertTrue("UserService 连接超时", latch.await(10, TimeUnit.SECONDS))
            check(requireNotNull(remote))
        } finally { Shizuku.unbindUserService(args, connection, true) }
    }
}
