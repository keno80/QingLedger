package io.qingledger.automation.shizuku

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.qingledger.domain.model.ShizukuCaptureRules
import io.qingledger.domain.model.ShizukuWindow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.exitProcess

/** Runs as shell/root under Shizuku. No Android Service, UiAutomation, or accessibility connection. */
class ReadOnlyCaptureUserService : IReadOnlyCaptureService.Stub() {
    private val operation = ReentrantLock()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val writers = Executors.newSingleThreadExecutor()
    private val processes = Collections.synchronizedSet(mutableSetOf<java.lang.Process>())
    private val pipes = Collections.synchronizedSet(mutableSetOf<ParcelFileDescriptor>())
    @Volatile private var destroyed = false

    override fun probe(): Bundle = guarded {
        val window = focusedWindow()
        physicalDisplay(window.displayId)
        if (!File("/system/bin/screencap").canExecute()) throw IOException("系统不支持 screencap")
        ShizukuCaptureProtocol.result(ShizukuCaptureProtocol.OK, window = window)
    }

    override fun capture(expectedWindowToken: String?, expectedDisplayId: Int): ReadOnlyCaptureResult {
        var image: ParcelFileDescriptor? = null
        val metadata = guarded {
            if (pipes.isNotEmpty()) return@guarded skip("前一次截图仍在传输")
            if (expectedWindowToken == null || !expectedWindowToken.matches(Regex("[a-fA-F0-9]{1,16}")) || expectedDisplayId < 0) {
                return@guarded skip("窗口标识无效")
            }
            if (!screenAvailable()) return@guarded skip("屏幕未亮起或设备已锁定")
            val before = focusedWindow()
            // Alipay remains notification-only. The shell service cannot capture arbitrary packages.
            if (before.sourcePackage != "com.tencent.mm") return@guarded skip("等待微信前台页面")
            if (before.token != expectedWindowToken || before.displayId != expectedDisplayId) return@guarded skip("前台窗口已切换")
            if (before.secure) return@guarded skip("安全窗口禁止截图")
            val physical = physicalDisplay(before.displayId)
            val bytes = runFixed(FixedRead.SCREENSHOT, physical)
            val after = focusedWindow()
            if (before != after || !screenAvailable() || physicalDisplay(after.displayId) != physical) {
                bytes.fill(0)
                return@guarded skip("截图期间窗口或屏幕状态变化")
            }
            if (bytes.size < 24) return@guarded skip("系统返回空截图")
            val descriptors = ParcelFileDescriptor.createPipe()
            val reader = descriptors[0]
            val writer = descriptors[1]
            pipes.add(writer)
            val timeout = timer.schedule({ runCatching { writer.close() } }, 4, TimeUnit.SECONDS)
            writers.execute {
                try { ParcelFileDescriptor.AutoCloseOutputStream(writer).use { it.write(bytes) } }
                catch (_: IOException) { /* Timeout/cancel closes the transient pipe. */ }
                finally {
                    bytes.fill(0)
                    timeout.cancel(false)
                    pipes.remove(writer)
                }
            }
            image = reader
            ShizukuCaptureProtocol.result(ShizukuCaptureProtocol.OK, window = before)
        }
        return ShizukuCaptureProtocol.imageResult(metadata, image)
    }

    private fun guarded(block: () -> Bundle): Bundle {
        if (destroyed) return skip("采集连接已关闭")
        if (Process.myUid() != 0 && Process.myUid() != 2000) {
            return ShizukuCaptureProtocol.result(ShizukuCaptureProtocol.UNSUPPORTED, "UserService 未以 ADB / Root 身份运行")
        }
        if (!operation.tryLock()) return skip("前一次采集仍在处理")
        return try { block() }
        catch (error: Exception) {
            // Do not log window dumps, page content, or screenshots.
            ShizukuCaptureProtocol.result(ShizukuCaptureProtocol.UNSUPPORTED, when (error) {
                is IOException -> error.message ?: "只读采集失败"
                else -> "系统窗口或显示接口不受支持（${error.javaClass.simpleName}）"
            })
        } finally { operation.unlock() }
    }

    private fun focusedWindow(): ShizukuWindow = ShizukuCaptureRules.parseFocusedWindow(
        runFixed(FixedRead.WINDOWS).toString(Charsets.UTF_8),
    ) ?: throw IOException("无法唯一识别前台窗口、安全标志或显示屏；已跳过")

    private fun screenAvailable(): Boolean =
        systemBoolean("power", "android.os.IPowerManager", "isInteractive") &&
            !systemBoolean("window", "android.view.IWindowManager", "isKeyguardLocked")

    private fun systemBoolean(service: String, interfaceName: String, method: String): Boolean {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, service) as? IBinder ?: throw IOException("系统状态服务不可用")
        val api = Class.forName("$interfaceName\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        return Class.forName(interfaceName).getMethod(method).invoke(api) as Boolean
    }

    /** Never pass a logical Android display id to screencap's physical display argument. */
    private fun physicalDisplay(logicalId: Int): Long {
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val getIds = globalClass.methods.firstOrNull { it.name == "getDisplayIds" && it.parameterCount == 0 }
        val ids = if (getIds != null) getIds.invoke(global) as IntArray else
            globalClass.getMethod("getDisplayIds", Boolean::class.javaPrimitiveType).invoke(global, false) as IntArray
        val mappings = mutableMapOf<Int, Long>()
        for (id in ids) {
            val info = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, id) ?: continue
            val address = info.javaClass.getField("address").get(info) ?: continue
            val physicalMethod = address.javaClass.methods.firstOrNull { it.name == "getPhysicalDisplayId" && it.parameterCount == 0 } ?: continue
            mappings[id] = physicalMethod.invoke(address) as Long
        }
        // AOSP moved getPhysicalDisplayIds out of SurfaceControl on newer releases.
        // screencap documents this fixed SurfaceFlinger query as its authoritative ID source.
        val physicalIds = ShizukuCaptureRules.parsePhysicalDisplayIds(runFixed(FixedRead.DISPLAY_IDS).toString(Charsets.UTF_8))
        return ShizukuCaptureRules.physicalDisplayId(logicalId, mappings, physicalIds)
            ?: throw IOException("目标显示屏无法映射到唯一物理屏；已跳过")
    }

    private enum class FixedRead { WINDOWS, DISPLAY_IDS, SCREENSHOT }

    private fun runFixed(read: FixedRead, physicalId: Long? = null): ByteArray {
        check(!destroyed)
        val command = when (read) {
            FixedRead.WINDOWS -> listOf("/system/bin/dumpsys", "window", "-a")
            FixedRead.DISPLAY_IDS -> listOf("/system/bin/dumpsys", "SurfaceFlinger", "--display-id")
            FixedRead.SCREENSHOT -> listOf("/system/bin/screencap", "-p", "-d", java.lang.Long.toUnsignedString(requireNotNull(physicalId)))
        }
        val max = when (read) {
            FixedRead.WINDOWS -> 4 * 1024 * 1024
            FixedRead.DISPLAY_IDS -> 64 * 1024
            FixedRead.SCREENSHOT -> ShizukuImageTransport.MAX_BYTES
        }
        val timeoutMillis = if (read == FixedRead.SCREENSHOT) 2500L else 1500L
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        processes.add(process)
        val timeout = timer.schedule({ process.destroyForcibly() }, timeoutMillis, TimeUnit.MILLISECONDS)
        try {
            val output = ByteArrayOutputStream()
            process.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > max) throw IOException("采集数据超过大小上限")
                    output.write(buffer, 0, count)
                }
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw IOException("系统采集命令超时")
            if (process.exitValue() != 0) throw IOException("系统采集失败、超时或权限不足")
            return output.toByteArray()
        } finally {
            timeout.cancel(false)
            process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            processes.remove(process)
        }
    }

    override fun destroy() {
        destroyed = true
        synchronized(processes) { processes.forEach { it.destroyForcibly() }; processes.clear() }
        synchronized(pipes) { pipes.forEach { runCatching { it.close() } }; pipes.clear() }
        writers.shutdownNow()
        timer.shutdownNow()
        // The Shizuku destroy transaction is only meaningful in the dedicated shell/root process.
        if (Process.myUid() == 0 || Process.myUid() == 2000) exitProcess(0)
    }

    private fun skip(detail: String) = ShizukuCaptureProtocol.result(ShizukuCaptureProtocol.SKIP, detail)
}
