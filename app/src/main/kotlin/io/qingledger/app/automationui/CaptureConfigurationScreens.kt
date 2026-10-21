package io.qingledger.app.automationui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.settings.*
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.CaptureMethod
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ObserveCaptureAccess(capture: CaptureViewModel, onResume: () -> Unit = {}) {
    val owner = LocalLifecycleOwner.current
    val refresh by rememberUpdatedState(onResume)
    DisposableEffect(owner, capture) {
        capture.refreshAccess(); refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { capture.refreshAccess(); refresh() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun CapturePermissionsScreen(capture: CaptureViewModel, onSources: () -> Unit) {
    val context = LocalContext.current
    val method by capture.method.collectAsStateWithLifecycle()
    val access by capture.access.collectAsStateWithLifecycle()
    val connected by capture.connected.collectAsStateWithLifecycle()
    val settings by capture.settings.collectAsStateWithLifecycle()
    val busy by capture.busy.collectAsStateWithLifecycle()
    var notices by remember { mutableStateOf(capture.notificationsEnabled()) }
    var promotion by remember { mutableStateOf(capture.promotionAllowed()) }
    var batteryExempt by remember { mutableStateOf(false) }
    var methods by rememberSaveable { mutableStateOf(false) }
    ObserveCaptureAccess(capture) {
        notices = capture.notificationsEnabled(); promotion = capture.promotionAllowed()
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val requestNotices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notices = capture.notificationsEnabled(); promotion = capture.promotionAllowed() }
    fun open(intent: Intent) { if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) }
    fun appDetails() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    val supported = method == CaptureMethod.ACCESSIBILITY || method == CaptureMethod.NOTIFICATION
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection { SettingsNavigationRow(method.label + "方式", if (supported) "采集所选应用的交易信息" else "尚未接入", LedgerIcons.Privacy, { methods = true }) }
        SettingsSection("必要权限") {
            SettingsNavigationRow(if (method == CaptureMethod.ACCESSIBILITY) "无障碍服务" else if (method == CaptureMethod.NOTIFICATION) "通知访问" else method.label, if (!supported) "后端尚未接入" else "${if (access) "已授权" else "未授权"} · ${if (connected) "已连接" else "未连接"}", LedgerIcons.Scan, {
                when (method) { CaptureMethod.ACCESSIBILITY -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); CaptureMethod.NOTIFICATION -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); else -> methods = true }
            }) { CaptureBadge(if (supported && access && connected) "正常" else if (!supported) "开发中" else "去配置", supported && access && connected) }
            SettingsRowDivider()
            SettingsNavigationRow("支付来源", if (settings.sources.isEmpty()) "尚未开启来源" else "已开启 ${settings.sources.size} 个来源", LedgerIcons.Wallet, onSources) { CaptureBadge(if (settings.sources.isEmpty()) "去配置" else "已配置", settings.sources.isNotEmpty()) }
            if (method == CaptureMethod.NOTIFICATION && access && !connected) {
                SettingsRowDivider()
                SettingsNavigationRow("重新连接监听", "已授权但未连接时重试", LedgerIcons.Automation, capture::reconnect, !busy)
            }
        }
        SettingsSection("运行保障") {
            SettingsNavigationRow("后台运行", "根据系统设置允许后台活动", LedgerIcons.Automation, ::appDetails) { CaptureBadge("去配置") }
            SettingsRowDivider()
            SettingsNavigationRow("电池优化", if (batteryExempt) "已忽略电池优化" else "系统限制可能造成漏记", LedgerIcons.Privacy, { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { CaptureBadge(if (batteryExempt) "已配置" else "去配置", batteryExempt) }
            SettingsRowDivider()
            SettingsNavigationRow("识别通知", if (notices) "识别时显示进度与结果" else "未开启，仅影响通知展示", LedgerIcons.Ledger, {
                if (Build.VERSION.SDK_INT >= 33 && !notices) requestNotices.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { CaptureBadge(if (notices) "已开启" else "去配置", notices) }
            if (Build.VERSION.SDK_INT >= 36) {
                SettingsRowDivider()
                SettingsNavigationRow("实时更新", if (promotion) "已允许，展示形式由系统决定" else "未允许，使用普通通知", LedgerIcons.Automation, {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                    else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { CaptureBadge(if (promotion) "已允许" else "去配置", promotion) }
            }
        }
        Text("只在授权且开启采集后读取所选来源；不执行点击或付款操作。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SettingsSection("其他方式") {
            SettingsNavigationRow("Shizuku + ADB", "尚未接入 · 后续独立配置", LedgerIcons.Scan, { methods = true }) { CaptureBadge("开发中") }
            SettingsRowDivider()
            SettingsNavigationRow("Root", "尚未接入 · 后续独立授权", LedgerIcons.Privacy, { methods = true }) { CaptureBadge("开发中") }
        }
    }
    CaptureMethodSheet(methods, method, busy, capture) { methods = false }
}

@Composable
fun CaptureSourcesScreen(capture: CaptureViewModel) {
    val context = LocalContext.current
    val method by capture.method.collectAsStateWithLifecycle()
    val settings by capture.settings.collectAsStateWithLifecycle()
    val busy by capture.busy.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    var scope by rememberSaveable { mutableStateOf(false) }
    var accountHelp by rememberSaveable { mutableStateOf(false) }
    ObserveCaptureAccess(capture) { resumed++ }
    val sources = listOf("com.tencent.mm", "com.eg.android.AlipayGphone") + if (io.qingledger.app.BuildConfig.DEBUG) listOf(if (method == CaptureMethod.NOTIFICATION) "com.android.shell" else "io.qingledger.paymentsimulator") else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection {
            sources.forEachIndexed { index, source ->
                if (index > 0) SettingsRowDivider()
                val installed = remember(source, resumed) { source == "com.android.shell" || try { @Suppress("DEPRECATION") context.packageManager.getPackageInfo(source, 0); true } catch (_: Exception) { false } }
                val enabled = source in settings.sources
                SettingsNavigationRow(sourceLabel(source), when { !installed -> "未安装"; source in setOf("io.qingledger.paymentsimulator", "com.android.shell") -> "独立模拟来源，仅用于测试"; enabled -> "已开启 · 页面与版本覆盖待验证"; else -> "尚未开启" }, LedgerIcons.Wallet, { if (installed && !busy) capture.source(source, !enabled) }, installed && !busy) {
                    Switch(checked = enabled, onCheckedChange = { capture.source(source, it) }, enabled = installed && !busy)
                }
            }
        }
        SettingsSection("来源说明") {
            SettingsNavigationRow("识别范围", "付款成功后入账，失败与取消不入账", LedgerIcons.Scan, { scope = true })
            SettingsRowDivider()
            SettingsNavigationRow("账户关联", "零钱与银行卡分开记录", LedgerIcons.Housing, { accountHelp = true })
        }
        Text("来源需单独开启。版本或页面变化时可能识别失败，可手动补记。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    CaptureScopeSheet(scope) { scope = false }
    LedgerBottomSheet(accountHelp, "账户关联", { accountHelp = false }) {
        Text("付款选择页可暂存当次付款账户，成功后再关联账单。零钱与不同尾号银行卡分别记录；无法确定时保留未指定账户，可在账单中修改。", fontSize = 14.sp)
    }
}
