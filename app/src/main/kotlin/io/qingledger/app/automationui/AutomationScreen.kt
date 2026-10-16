package io.qingledger.app.automationui

import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.PendingCandidate
import io.qingledger.domain.model.CaptureMethod
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*

@Composable
fun AutomationScreen(capture: CaptureViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings by capture.settings.collectAsStateWithLifecycle()
    val method by capture.method.collectAsStateWithLifecycle()
    val access by capture.access.collectAsStateWithLifecycle()
    val connected by capture.connected.collectAsStateWithLifecycle()
    val busy by capture.busy.collectAsStateWithLifecycle()
    val message by capture.message.collectAsStateWithLifecycle()
    val runtimeError by capture.runtimeError.collectAsStateWithLifecycle()
    val lastEvent by capture.lastEvent.collectAsStateWithLifecycle()
    var noticesEnabled by remember { mutableStateOf(capture.notificationsEnabled()) }
    var promotionAllowed by remember { mutableStateOf(capture.promotionAllowed()) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        noticesEnabled = capture.notificationsEnabled()
        promotionAllowed = capture.promotionAllowed()
    }
    DisposableEffect(owner, capture) {
        capture.refreshAccess()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            capture.refreshAccess()
            noticesEnabled = capture.notificationsEnabled()
            promotionAllowed = capture.promotionAllowed()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val implemented = method == CaptureMethod.ACCESSIBILITY || method == CaptureMethod.NOTIFICATION
        SettingsGroup("采集方式（三选一）") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.SHIZUKU, CaptureMethod.ROOT).forEach { choice ->
                    Button(onClick = { capture.selectMethod(choice) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(choice.label + if (method == choice) " · 已选择" else "")
                    }
                }
                Text("切换方式会暂停采集，确认授权和来源后再开启。")
            }
        }
        val title = when {
            !implemented -> "${method.label}尚未接入"
            !access -> "需要${method.label}授权"
            !settings.enabled -> "采集已暂停"
            settings.sources.isEmpty() -> "请选择通知来源"
            !connected -> "监听未连接"
            else -> "正在采集所选来源"
        }
        StatusCard(title, "识别到明确金额和收支方向后直接入账，无需确认；不准确时可在账单中编辑或删除。金额或方向不明确的通知将跳过。")
        SettingsGroup("识别通知") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (!noticesEnabled) "通知未开启：识别仍正常运行，但无法显示进度和结果。" else "识别中显示进度；支持实时更新时，结果胶囊保留4秒，再转为普通通知，30秒后自动收起。")
                Text(if (Build.VERSION.SDK_INT < 36) "当前系统使用普通进度通知。" else if (promotionAllowed) "已允许实时更新，具体展示形式由系统决定。" else "实时更新未允许，使用普通通知。")
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !noticesEnabled) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, modifier = Modifier.fillMaxWidth()) { Text(if (noticesEnabled) "通知设置" else "开启识别通知") }
                if (Build.VERSION.SDK_INT >= 36) Button(onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, modifier = Modifier.fillMaxWidth()) { Text("实时更新设置") }
            }
        }
        SettingsDescription("${method.label}状态", "权限：${if (access) "已授权" else "未授权"} · 监听：${if (connected) "已连接" else "未连接"}")
        if (method == CaptureMethod.ACCESSIBILITY) {
            StatusCard("无障碍访问说明", "开启后只读取所选支付应用可见交易成功页面的文字，识别后直接入账。不保存页面原文，不点击、不操作付款。页面覆盖仍待验证，可随时暂停或在系统设置关闭。")
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text("打开无障碍设置") }
            if (access && !connected) Text("已授权但服务未连接，请在系统设置重新开启轻账无障碍记账。")
        } else if (method == CaptureMethod.NOTIFICATION) {
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("打开通知访问设置") }
            if (access && !connected) Button(onClick = capture::reconnect, enabled = !busy) { Text("重新连接监听") }
        } else StatusCard("增强方式开发中", if (method == CaptureMethod.SHIZUKU) "后续接入 Shizuku 服务、ADB 运行身份和采集能力检测；ADB 权限不等于 Root。" else "后续接入独立 Root 授权和采集能力检测。当前没有执行 su 命令。")
        Button(onClick = { capture.enable(!settings.enabled) }, enabled = !busy && (settings.enabled || (implemented && access)), modifier = Modifier.fillMaxWidth(), minHeight = 48.dp) { Text(if (settings.enabled) "暂停采集" else "开启采集") }
        SettingsGroup("支付来源（默认关闭）") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (listOf("com.eg.android.AlipayGphone", "com.tencent.mm") + if (io.qingledger.app.BuildConfig.DEBUG) listOf(if (method == CaptureMethod.NOTIFICATION) "com.android.shell" else "io.qingledger.paymentsimulator") else emptyList()).forEach { source ->
                    val installed = remember(source) { source == "com.android.shell" || try { @Suppress("DEPRECATION") context.packageManager.getPackageInfo(source, 0); true } catch (_: Exception) { false } }
                    SettingsDescription(sourceLabel(source), if (!installed) "未安装" else if (source == "com.android.shell" || source == "io.qingledger.paymentsimulator") "独立模拟来源，仅验证采集闭环，不代表真实支付适配" else "按所选方式识别后直接入账；页面与版本覆盖仍待验证")
                    Button(onClick = { capture.source(source, source !in settings.sources) }, enabled = installed && !busy, minHeight = 48.dp) { Text((if (source in settings.sources) "关闭" else "启用") + sourceLabel(source) + "来源") }
                }
            }
        }
        if (lastEvent == null) Text("本次运行暂无新交易事件，这不表示监听故障。")
        else Text("本次最近事件：" + Instant.ofEpochMilli(lastEvent!!).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")))
        (message ?: runtimeError)?.let { StatusCard("状态提示", it); Button(onClick = capture::clearMessage) { Text("关闭采集提示") } }
        Button(onClick = capture::clearResolved, enabled = !busy) { Text("隐藏已处理事件") }
    }
}

fun LazyListScope.captureEvents(rows: List<PendingCandidate>, capture: CaptureViewModel, onOpen: (String) -> Unit) {
    item("events-heading") { Text("自动记账记录（最多显示 200 条）") }
    if (rows.isEmpty()) item("events-empty") { StatusCard("暂无事件", "完成所选方式授权、开启采集并选择支付来源后，识别的交易才会进入这里。") }
    items(rows, key = { "candidate-${it.id}" }) { row ->
        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceLabel(row.sourcePackage) + " · " + when (row.state) { "PENDING" -> "旧版未处理事件"; "AUTO_POSTED" -> "自动入账"; "CONFIRMED" -> "人工入账"; "MERGED" -> "已关联原账单"; "SKIPPED" -> "未识别完整，已跳过"; else -> "已忽略" })
                Text(row.amountMinor?.let(::formatMoney) ?: "金额待补充")
                Text(when (row.state) {
                    "AUTO_POSTED" -> "已直接生成账单，可在账单页修改。"
                    "MERGED" -> "同一通知会话更新，保留原账单及用户修改。"
                    "SKIPPED" -> reviewLabel(row.reason).substringBefore("，") + "。未生成账单，如需记录可手动记账。"
                    else -> reviewLabel(row.reason)
                })
                Text(Instant.ofEpochMilli(row.receivedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")))
                if (row.state == "PENDING") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("旧版事件不会追溯入账，可手动补记。")
                    Button(onClick = { capture.ignore(row.id) }) { Text("忽略事件") }
                }
            }
        }
    }
}
