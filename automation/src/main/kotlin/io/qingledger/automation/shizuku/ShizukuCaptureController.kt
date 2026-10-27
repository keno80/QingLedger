package io.qingledger.automation.shizuku

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.qingledger.domain.model.ShizukuCaptureRules
import io.qingledger.domain.model.ShizukuFrameLease
import io.qingledger.domain.model.ShizukuWindow
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/** The foreground service owns this controller; it never requests permission implicitly. */
class ShizukuCaptureController(
    context: Context,
    private val onFrame: suspend (Frame) -> Unit,
) : Closeable {
    data class Frame(
        val sourcePackage: String,
        val activity: String,
        val displayId: Int,
        val bitmap: Bitmap,
        val generation: Long,
        val windowToken: String = "",
    )

    private data class CaptureRequest(val sources: Set<String>, val generation: Long)
    private data class LiveFrame(val frame: Frame, val lease: ShizukuFrameLease, val service: IReadOnlyCaptureService)
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val epoch = AtomicLong()
    private val captureLock = Any()
    private val descriptor = AtomicReference<ParcelFileDescriptor?>()
    private val mutableStatus = MutableStateFlow(ShizukuCaptureStatus(ShizukuConnectionState.NOT_RUNNING))
    val status: StateFlow<ShizukuCaptureStatus> = mutableStatus.asStateFlow()
    private val mutableLastIssue = MutableStateFlow<String?>(null)
    val lastIssue: StateFlow<String?> = mutableLastIssue.asStateFlow()
    @Volatile private var remote: IReadOnlyCaptureService? = null
    @Volatile private var requested: CaptureRequest? = null
    @Volatile private var closed = false
    @Volatile private var activeFrame: LiveFrame? = null
    private var captureJob: Job? = null
    private var connectionTimeout: Job? = null
    private var bound = false
    private var activeConnection: ServiceConnection? = null
    private var remoteDeath: IBinder.DeathRecipient? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(app, ReadOnlyCaptureUserService::class.java))
        .tag("qingledger-read-only-capture")
        .processNameSuffix("readonly_capture")
        .daemon(false)
        .version(3)

    private val receivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val deadListener = Shizuku.OnBinderDeadListener {
        invalidateCapture()
        main.post { disconnected("Shizuku 服务已停止") }
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST_CODE && !closed) {
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else {
                invalidateCapture()
                disconnectRemote()
                mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.PERMISSION_REQUIRED, "Shizuku 授权未获准")
            }
        }
    }
    private fun createConnection(): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (closed || !bound || activeConnection !== this || service == null) return
            val candidate = IReadOnlyCaptureService.Stub.asInterface(service)
            remote = candidate
            val death = IBinder.DeathRecipient {
                if (remote === candidate) {
                    invalidateCapture()
                    main.post { if (remote === candidate) disconnected("只读采集服务已断开") }
                }
            }
            remoteDeath = death
            try { service.linkToDeath(death, 0) }
            catch (_: Exception) { disconnected("只读采集服务已断开"); return }
            scope.launch {
                try {
                    val capability = candidate.probe()
                    main.post {
                        if (!closed && bound && remote === candidate) {
                            if (capability.getString("status") != ShizukuCaptureProtocol.OK) {
                                unsupported(capability.getString("detail") ?: "窗口或显示接口检查未通过")
                            } else {
                                connectionTimeout?.cancel()
                                mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.CONNECTED)
                                beginPolling()
                            }
                        }
                    }
                } catch (_: Exception) { main.post { if (remote === candidate) disconnected("只读采集能力检查失败") } }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (activeConnection !== this) return
            invalidateCapture()
            disconnected("只读采集服务已断开")
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshStatus()
    }

    /** Refreshes installed/running/permission state without starting a service. */
    fun refreshStatus() = onMain {
        if (!Shizuku.pingBinder()) {
            mutableStatus.value = ShizukuCaptureStatus(
                if (isManagerInstalled()) ShizukuConnectionState.NOT_RUNNING else ShizukuConnectionState.NOT_INSTALLED,
            )
        } else if (!supportsUserService()) {
            unsupported("需要支持 UserService 的 Shizuku 12 或更新版本")
        } else if (!hasPermission()) {
            invalidateCapture()
            disconnectRemote()
            mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.PERMISSION_REQUIRED)
        } else if (remote == null && !bound) {
            mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.DISCONNECTED, "点击连接以检查采集能力")
        }
    }

    fun requestPermission() = onMain {
        refreshStatus()
        if (!Shizuku.pingBinder() || !supportsUserService()) return@onMain
        if (hasPermission()) connect()
        else runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }.onFailure {
            mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.PERMISSION_REQUIRED, "无法请求授权，请检查 Shizuku")
        }
    }

    /** Checks the live grant independently of whether the read-only UserService is connected. */
    fun isAuthorized(): Boolean = Shizuku.pingBinder() && hasPermission()

    fun connect() = onMain {
        refreshStatus()
        if (!Shizuku.pingBinder() || !hasPermission() || mutableStatus.value.state == ShizukuConnectionState.UNSUPPORTED) return@onMain
        if (remote != null) {
            if (mutableStatus.value.state == ShizukuConnectionState.CONNECTED) beginPolling()
            return@onMain
        }
        if (bound) return@onMain
        mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.CONNECTING)
        try {
            bound = true
            val connection = createConnection()
            activeConnection = connection
            Shizuku.bindUserService(args, connection)
            connectionTimeout = scope.launch {
                delay(8000)
                main.post { if (activeConnection === connection && bound && mutableStatus.value.state == ShizukuConnectionState.CONNECTING) disconnected("连接只读采集服务超时") }
            }
        } catch (_: Exception) { disconnected("无法连接只读采集服务") }
    }

    fun start(sources: Set<String>, generation: Long) = onMain {
        invalidateCapture()
        mutableLastIssue.value = null
        requested = CaptureRequest(sources.toSet() - app.packageName, generation)
        if (mutableStatus.value.state == ShizukuConnectionState.CONNECTED && remote != null) beginPolling() else connect()
    }

    /** Pausing kills the UserService and closes any active image pipe. A later start reconnects. */
    fun stop() {
        requested = null
        mutableLastIssue.value = null
        invalidateCapture()
        onMain {
            disconnectRemote()
            if (mutableStatus.value.state == ShizukuConnectionState.CONNECTED || mutableStatus.value.state == ShizukuConnectionState.CONNECTING) {
                mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.DISCONNECTED, "采集已暂停")
            }
        }
    }

    /** Only valid while the original awaited onFrame callback is still processing this frame. */
    suspend fun isCurrent(frame: Frame): Boolean = withContext(Dispatchers.IO) {
        val live = activeFrame?.takeIf { it.frame === frame } ?: return@withContext false
        val service = remote?.takeIf { it === live.service } ?: return@withContext false
        if (closed || live.lease.epoch != epoch.get() || frame.windowToken.isBlank() || !hasPermission() ||
            !power.isInteractive || keyguard.isDeviceLocked || keyguard.isKeyguardLocked) return@withContext false
        val deadline = scope.launch {
            delay(8000)
            main.post {
                if (activeFrame === live && epoch.get() == live.lease.epoch) disconnected("OCR 后的前台窗口复核超时")
            }
        }
        try {
            val probe = service.probe()
            ensureActive()
            if (activeFrame !== live || closed || remote !== service) return@withContext false
            val request = requested
            val after = if (probe.getString("status") == ShizukuCaptureProtocol.OK)
                ShizukuCaptureProtocol.window(probe)?.let(::canonicalWindow) else null
            val authorized = hasPermission()
            ensureActive()
            if (activeFrame !== live || requested !== request || closed || remote !== service) return@withContext false
            ShizukuCaptureRules.isCurrent(live.lease, after, request?.sources.orEmpty(), app.packageName,
                request?.generation, epoch.get(), power.isInteractive,
                !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked, authorized,
                mutableStatus.value.state == ShizukuConnectionState.CONNECTED && remote === service)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        finally { deadline.cancel() }
    }

    private fun beginPolling() {
        val request = requested ?: return
        if (captureJob?.isActive == true || remote == null) return
        val runEpoch = epoch.get()
        captureJob = scope.launch {
            while (isActive && runEpoch == epoch.get()) {
                val granted = hasPermission()
                if (runEpoch != epoch.get()) break
                if (!granted) {
                    main.post {
                        if (runEpoch == epoch.get()) {
                            requested = null
                            invalidateCapture()
                            disconnectRemote()
                            mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.PERMISSION_REQUIRED, "Shizuku 权限已撤销")
                        }
                    }
                    break
                }
                if (!power.isInteractive || keyguard.isDeviceLocked || keyguard.isKeyguardLocked || "com.tencent.mm" !in request.sources) {
                    mutableLastIssue.value = "等待亮屏解锁和已选择的微信来源"
                    delay(4000)
                    continue
                }
                try {
                    val deadline = scope.launch {
                        delay(12_000)
                        if (runEpoch == epoch.get()) main.post { if (runEpoch == epoch.get()) disconnected("只读采集超时，请重新连接") }
                    }
                    try { captureOnce(request, runEpoch, deadline) }
                    finally { deadline.cancel() }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: SecurityException) {
                    main.post {
                        if (runEpoch == epoch.get()) {
                            requested = null
                            invalidateCapture()
                            disconnectRemote()
                            mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.PERMISSION_REQUIRED, "Shizuku 权限已撤销")
                        }
                    }
                    break
                } catch (error: IOException) { if (runEpoch == epoch.get()) mutableLastIssue.value = error.message }
                catch (_: Exception) { main.post { if (runEpoch == epoch.get()) disconnected("采集连接异常，请重新连接") }; break }
                delay(if (mutableLastIssue.value == "等待微信前台页面") 4000 else 2000)
            }
        }
    }

    private suspend fun captureOnce(request: CaptureRequest, runEpoch: Long, acquisitionDeadline: Job) {
        val service = remote ?: return
        val probe = service.probe()
        ensureActiveCapture(runEpoch)
        if (probe.getString("status") == ShizukuCaptureProtocol.UNSUPPORTED) {
            main.post { if (runEpoch == epoch.get() && remote === service) unsupported(probe.getString("detail") ?: "系统窗口探测不受支持") }
            return
        }
        val before = ShizukuCaptureProtocol.window(probe) ?: return
        if (before.sourcePackage !in request.sources || before.sourcePackage == app.packageName) {
            mutableLastIssue.value = "等待微信前台页面"
            return
        }
        if (before.secure) { mutableLastIssue.value = "安全窗口禁止截图"; return }
        val result = service.capture(before.token, before.displayId)
        val metadata = result.metadata
        val image = result.image
        if (metadata.getString("status") != ShizukuCaptureProtocol.OK || image == null) {
            image?.close()
            if (runEpoch == epoch.get()) mutableLastIssue.value = metadata.getString("detail") ?: "截图不可用"
            if (metadata.getString("status") == ShizukuCaptureProtocol.UNSUPPORTED) main.post {
                if (runEpoch == epoch.get() && remote === service) unsupported(metadata.getString("detail") ?: "系统截图不受支持")
            }
            return
        }
        var bitmap: Bitmap? = null
        try {
            synchronized(captureLock) {
                if (runEpoch != epoch.get()) throw CancellationException("采集配置已变化")
                descriptor.set(image)
            }
            ensureActiveCapture(runEpoch)
            bitmap = ShizukuImageTransport.readBitmap(image)
            ensureActiveCapture(runEpoch)
            val after = ShizukuCaptureProtocol.window(service.probe()) ?: return
            if (!ShizukuCaptureRules.acceptFrame(before, after, request.sources, app.packageName,
                    request.generation, requested?.generation ?: Long.MIN_VALUE,
                    power.isInteractive, !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) || !hasPermission()) return
            if (ShizukuImageTransport.isBlank(bitmap)) { mutableLastIssue.value = "截图为空或受系统保护"; return }
            ensureActiveCapture(runEpoch)
            mutableLastIssue.value = null
            // Bitmap lifetime is limited to this awaited callback. Consumers must finish OCR here.
            val window = canonicalWindow(before)
            val frame = Frame(window.sourcePackage, window.activity, window.displayId, bitmap, request.generation, window.token)
            val live = LiveFrame(frame, ShizukuFrameLease(window, request.generation, runEpoch), service)
            synchronized(captureLock) {
                if (epoch.get() != runEpoch) throw CancellationException("采集配置已变化")
                activeFrame = live
            }
            // The processor owns its OCR timeout. Do not count OCR against the transport deadline.
            acquisitionDeadline.cancel()
            try { onFrame(frame) }
            finally { synchronized(captureLock) { if (activeFrame === live) activeFrame = null } }
        } finally {
            descriptor.compareAndSet(image, null)
            runCatching { image.close() }
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private suspend fun ensureActiveCapture(runEpoch: Long) {
        withContext(Dispatchers.IO) { ensureActive() }
        if (runEpoch != epoch.get()) throw CancellationException("采集配置已变化")
    }

    private fun canonicalWindow(window: ShizukuWindow): ShizukuWindow = window.copy(
        activity = ComponentName.unflattenFromString("${window.sourcePackage}/${window.activity}")?.className ?: window.activity,
    )

    /** Bypass the API's positive permission cache so a live revoked grant cannot keep polling. */
    private fun hasPermission(): Boolean = runCatching {
        val binder = Shizuku.getBinder() ?: return@runCatching false
        IShizukuService.Stub.asInterface(binder).checkSelfPermission()
    }.getOrDefault(false)

    private fun supportsUserService(): Boolean = runCatching {
        !Shizuku.isPreV11() && Shizuku.getVersion() >= 12
    }.getOrDefault(false)

    private fun invalidateCapture() {
        synchronized(captureLock) {
            epoch.incrementAndGet()
            activeFrame = null
            captureJob?.cancel()
            captureJob = null
            descriptor.getAndSet(null)?.let { runCatching { it.close() } }
        }
    }

    private fun disconnectRemote() {
        connectionTimeout?.cancel()
        connectionTimeout = null
        remoteDeath?.let { death -> remote?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } } }
        remoteDeath = null
        remote = null
        val oldConnection = activeConnection
        activeConnection = null
        if (bound) {
            bound = false
            runCatching { Shizuku.unbindUserService(args, oldConnection, true) }
        }
    }

    private fun disconnected(detail: String) {
        if (closed) return
        requested = null
        invalidateCapture()
        disconnectRemote()
        mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.DISCONNECTED, detail)
    }

    private fun unsupported(detail: String) {
        requested = null
        invalidateCapture()
        disconnectRemote()
        mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.UNSUPPORTED, detail)
    }

    private fun isManagerInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    private fun onMain(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!closed) action() }
        else main.post { if (!closed) action() }
    }

    override fun close() {
        if (closed) return
        closed = true
        requested = null
        invalidateCapture()
        disconnectRemote()
        Shizuku.removeBinderReceivedListener(receivedListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        mutableStatus.value = ShizukuCaptureStatus(ShizukuConnectionState.DISCONNECTED, "采集连接已释放")
        mutableLastIssue.value = null
        scope.cancel()
    }

    companion object { private const val PERMISSION_REQUEST_CODE = 7401 }
}
