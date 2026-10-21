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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.settings.*
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.CaptureMethod
import top.yukonga.miuix.kmp.basic.*

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
fun CapturePermissionsScreen(capture: CaptureViewModel) {
    val context = LocalContext.current
    var notices by remember { mutableStateOf(capture.notificationsEnabled()) }
    var promotion by remember { mutableStateOf(capture.promotionAllowed()) }
    var batteryExempt by remember { mutableStateOf(false) }
    ObserveCaptureAccess(capture) {
        notices = capture.notificationsEnabled(); promotion = capture.promotionAllowed()
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val requestNotices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notices = capture.notificationsEnabled(); promotion = capture.promotionAllowed() }
    fun open(intent: Intent) { if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) }
    fun appDetails() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
    }
}

@Composable
fun CaptureSourcesScreen(capture: CaptureViewModel) {
    val context = LocalContext.current
    val method by capture.method.collectAsStateWithLifecycle()
    val settings by capture.settings.collectAsStateWithLifecycle()
    val busy by capture.busy.collectAsStateWithLifecycle()
    var resumed by remember { mutableIntStateOf(0) }
    ObserveCaptureAccess(capture) { resumed++ }
    val sources = listOf("com.tencent.mm", "com.eg.android.AlipayGphone") + if (io.qingledger.app.BuildConfig.DEBUG) listOf(if (method == CaptureMethod.NOTIFICATION) "com.android.shell" else "io.qingledger.paymentsimulator") else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection {
            sources.forEachIndexed { index, source ->
                if (index > 0) SettingsRowDivider()
                val installed = remember(source, resumed) { source == "com.android.shell" || try { @Suppress("DEPRECATION") context.packageManager.getPackageInfo(source, 0); true } catch (_: Exception) { false } }
                val enabled = source in settings.sources
                SettingsNavigationRow(sourceLabel(source), when { !installed -> "未安装"; source in setOf("io.qingledger.paymentsimulator", "com.android.shell") -> "独立模拟来源，仅用于测试"; enabled && source == "com.eg.android.AlipayGphone" -> "通知入账 · 付款页关联账户"; enabled -> "已开启 · 页面与版本覆盖待验证"; else -> "尚未开启" }, LedgerIcons.Wallet, { if (installed && !busy) capture.source(source, !enabled) }, installed && !busy) {
                    Switch(checked = enabled, onCheckedChange = { capture.source(source, it) }, enabled = installed && !busy)
                }
            }
        }
    }
}
