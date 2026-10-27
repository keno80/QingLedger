package io.qingledger.app.automationui

import android.content.Intent
import android.provider.Settings
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.qingledger.app.settings.*
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AutomationScreen(capture: CaptureViewModel, onPermissions: () -> Unit, onSources: () -> Unit, onLogs: () -> Unit) {
    ObserveCaptureAccess(capture)
    val settings by capture.settings.collectAsStateWithLifecycle()
    val method by capture.method.collectAsStateWithLifecycle()
    val access by capture.access.collectAsStateWithLifecycle()
    val connected by capture.connected.collectAsStateWithLifecycle()
    val busy by capture.busy.collectAsStateWithLifecycle()
    val overview by capture.overview.collectAsStateWithLifecycle()
    val message by capture.message.collectAsStateWithLifecycle()
    val runtimeError by capture.runtimeError.collectAsStateWithLifecycle()
    var methods by rememberSaveable { mutableStateOf(false) }
    var booking by rememberSaveable { mutableStateOf(false) }
    var summary by rememberSaveable { mutableStateOf(false) }
    var notices by remember { mutableStateOf(capture.notificationsEnabled()) }
    ObserveCaptureAccess(capture) { notices = capture.notificationsEnabled() }
    val requestNotices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notices = capture.notificationsEnabled(); if (notices) capture.dailySummary(true) }
    val status = CaptureDisplayRules.status(method, settings.enabled, access, connected, settings.sources.size)
    val supported = method in setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.NOTIFICATION, CaptureMethod.SHIZUKU)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (settings.enabled) "自动记账已开启" else "自动记账已暂停", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Switch(checked = settings.enabled, onCheckedChange = { capture.enable(it) }, enabled = !busy && (settings.enabled || supported && access))
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(LedgerIcons.Privacy, null, Modifier.size(14.dp), tint = LedgerColors.accentContent)
                Text(method.label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                CaptureBadge(status, status == "运行正常")
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .12f)))
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("今日自动记账", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(overview.todayCount?.let { "$it 笔" } ?: "—", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("最近识别", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    val recent = overview.latest
                    Text(if (recent == null) "暂无记录" else Instant.ofEpochMilli(recent.receivedAt).atZone(ZoneId.of(overview.zoneId)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), fontSize = 13.sp)
                    if (recent != null) Text(overview.latestMerchant?.takeIf { it.isNotBlank() } ?: (sourceLabel(recent.sourcePackage) + " · " + CaptureDisplayRules.stateLabel(recent.state)), fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        SettingsSection {
            SettingsNavigationRow("采集方式", method.label, LedgerIcons.Scan, { methods = true })
            SettingsRowDivider()
            SettingsNavigationRow("入账方式", if (settings.bookingMode == CaptureBookingMode.DIRECT) "直接入账" else "确认后入账", LedgerIcons.Ledger, { booking = true })
            SettingsRowDivider()
            SettingsNavigationRow("权限与运行", "后台、电池与通知设置", LedgerIcons.Privacy, onPermissions)
            SettingsRowDivider()
            SettingsNavigationRow("采集来源", if (settings.sources.isEmpty()) "尚未开启来源" else settings.sources.joinToString("、") { sourceLabel(it) }, LedgerIcons.Wallet, onSources)
        }
        SettingsSection {
            SettingsNavigationRow("今日收支通知", if (!notices) "通知未授权" else if (settings.dailySummaryEnabled) "展示当日收入与支出" else "已关闭", LedgerIcons.Ledger, { summary = true }) {
                Switch(settings.dailySummaryEnabled, { enabled ->
                    if (enabled && !notices && Build.VERSION.SDK_INT >= 33) requestNotices.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else capture.dailySummary(enabled)
                }, enabled = !busy)
            }
        }
        SettingsSection("记录") {
            SettingsNavigationRow("采集记录", "查看入账、跳过和关联记录", LedgerIcons.Archive, onLogs)
        }
        (message ?: runtimeError)?.let { text ->
            SettingsSection { SettingsNavigationRow("状态提示", text, LedgerIcons.Privacy, capture::clearMessage) { Text("关闭", fontSize = 11.sp) } }
        }
    }
    CaptureMethodSheet(methods, method, busy, capture) { methods = false }
    LedgerBottomSheet(booking, "入账方式", { booking = false }) {
        SettingsSection {
            CaptureBookingMode.entries.forEachIndexed { index, value ->
                if (index > 0) SettingsRowDivider()
                SettingsNavigationRow(if (value == CaptureBookingMode.DIRECT) "直接入账" else "确认后入账",
                    if (value == CaptureBookingMode.DIRECT) "识别成功后自动入账" else "先编辑确认，再保存到账本", LedgerIcons.Ledger,
                    { capture.bookingMode(value); booking = false }, !busy) { CaptureBadge(if (settings.bookingMode == value) "已选择" else "选择", settings.bookingMode == value) }
            }
        }
    }
    LedgerBottomSheet(summary, "今日收支通知", { summary = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (notices) "展示今日收入、支出及待确认数量，与账本同步。" else "需要允许轻账发送通知。", fontSize = 13.sp)
            Text("锁屏隐藏具体金额，识别进度与结果继续单独展示。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Button(onClick = {
                if (!notices && Build.VERSION.SDK_INT >= 33) requestNotices.launch(Manifest.permission.POST_NOTIFICATIONS)
                else capture.dailySummary(!settings.dailySummaryEnabled)
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (!notices) "开启系统通知" else if (settings.dailySummaryEnabled) "关闭今日收支通知" else "开启今日收支通知") }
        }
    }
}

@Composable
internal fun CaptureBadge(label: String, good: Boolean = false) {
    Text(label, Modifier.background(if (good) LedgerColors.accentSurface else MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, color = if (good) LedgerColors.accentContent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun CaptureMethodSheet(show: Boolean, current: CaptureMethod, busy: Boolean, capture: CaptureViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf<CaptureMethod?>(null) }
    val shizuku by capture.shizukuStatus.collectAsStateWithLifecycle()
    ObserveCaptureAccess(capture)
    LaunchedEffect(show) { if (!show) unavailable = null }
    LedgerBottomSheet(show, "采集方式", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSection {
                listOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.SHIZUKU, CaptureMethod.ROOT).forEachIndexed { index, method ->
                    if (index > 0) SettingsRowDivider()
                    SettingsNavigationRow(method.label, when (method) { CaptureMethod.ACCESSIBILITY -> "读取所选来源的页面与交易通知"; CaptureMethod.SHIZUKU -> shizuku.label; else -> "尚未接入 · 后续独立授权" }, if (method == CaptureMethod.ROOT) LedgerIcons.Privacy else LedgerIcons.Scan, {
                        if (method == CaptureMethod.ACCESSIBILITY) {
                            val afterSelection = {
                                onClose()
                                if (!capture.hasAccessibilityAccess()) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                            if (current != method) capture.selectMethod(method, afterSelection) else afterSelection()
                        } else if (method == CaptureMethod.SHIZUKU) {
                            if (current != method) capture.selectMethod(method)
                            unavailable = method
                        } else unavailable = method
                    }, !busy) { CaptureBadge(if (method == current) "已选择" else if (method in setOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.SHIZUKU)) "可选择" else "开发中", method == current) }
                }
            }
            if (unavailable == CaptureMethod.SHIZUKU) {
                Text("Shizuku · ${shizuku.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(shizuku.detail ?: "安装并启动 Shizuku 后授权轻账，无需开启无障碍服务。", fontSize = 12.sp)
                Button(onClick = capture::requestShizuku, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("连接并授权") }
            } else unavailable?.let { Text("Root 后端尚未接入，当前不会执行 su 命令。", fontSize = 13.sp) }
            Text("切换方式会暂停采集，确认授权和来源后再开启。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
