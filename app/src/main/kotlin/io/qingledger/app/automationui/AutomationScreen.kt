package io.qingledger.app.automationui

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
    var scope by rememberSaveable { mutableStateOf(false) }
    val status = CaptureDisplayRules.status(method, settings.enabled, access, connected, settings.sources.size)
    val supported = method == CaptureMethod.ACCESSIBILITY || method == CaptureMethod.NOTIFICATION
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
            SettingsNavigationRow("权限与运行", when { !supported -> "${method.label}尚未接入"; !access -> "需要授权"; !connected -> "监听未连接"; else -> "访问权限已配置" }, LedgerIcons.Privacy, onPermissions) { CaptureBadge(if (supported && access && connected) "已配置" else "去配置", supported && access && connected) }
            SettingsRowDivider()
            SettingsNavigationRow("采集来源", if (settings.sources.isEmpty()) "尚未开启来源" else settings.sources.joinToString("、") { sourceLabel(it) }, LedgerIcons.Wallet, onSources)
        }
        SettingsSection("记录与规则") {
            SettingsNavigationRow("采集记录", "查看入账、跳过和关联记录", LedgerIcons.Archive, onLogs)
            SettingsRowDivider()
            SettingsNavigationRow("识别范围", "只在成功后记账，付款选择不入账", LedgerIcons.Scan, { scope = true })
        }
        Text("识别后直接入账，不准确时可编辑或删除账单。", Modifier.padding(horizontal = 3.dp), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        (message ?: runtimeError)?.let { text ->
            SettingsSection { SettingsNavigationRow("状态提示", text, LedgerIcons.Privacy, capture::clearMessage) { Text("关闭", fontSize = 11.sp) } }
        }
    }
    CaptureMethodSheet(methods, method, busy, capture) { methods = false }
    CaptureScopeSheet(scope) { scope = false }
}

@Composable
internal fun CaptureBadge(label: String, good: Boolean = false) {
    Text(label, Modifier.background(if (good) LedgerColors.accentSurface else MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, color = if (good) LedgerColors.accentContent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
internal fun CaptureMethodSheet(show: Boolean, current: CaptureMethod, busy: Boolean, capture: CaptureViewModel, onClose: () -> Unit) {
    var unavailable by remember { mutableStateOf<CaptureMethod?>(null) }
    LaunchedEffect(show) { if (!show) unavailable = null }
    LedgerBottomSheet(show, "采集方式", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSection {
                listOf(CaptureMethod.ACCESSIBILITY, CaptureMethod.SHIZUKU, CaptureMethod.ROOT).forEachIndexed { index, method ->
                    if (index > 0) SettingsRowDivider()
                    SettingsNavigationRow(method.label, when (method) { CaptureMethod.ACCESSIBILITY -> "读取所选来源的可见交易页面"; CaptureMethod.SHIZUKU -> "尚未接入 · 后续独立配置"; else -> "尚未接入 · 后续独立授权" }, if (method == CaptureMethod.ROOT) LedgerIcons.Privacy else LedgerIcons.Scan, {
                        if (method == CaptureMethod.ACCESSIBILITY) { if (current != method) capture.selectMethod(method); onClose() } else unavailable = method
                    }, !busy) { CaptureBadge(if (method == current) "已选择" else if (method == CaptureMethod.ACCESSIBILITY) "可选择" else "开发中", method == current) }
                }
            }
            unavailable?.let { Text(if (it == CaptureMethod.SHIZUKU) "Shizuku + ADB 后端尚未接入。ADB 权限不等于 Root。" else "Root 后端尚未接入，当前不会执行 su 命令。", fontSize = 13.sp) }
            Text("切换方式会暂停采集，确认授权和来源后再开启。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun CaptureScopeSheet(show: Boolean, onClose: () -> Unit) {
    LedgerBottomSheet(show, "识别范围", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("只读取已开启来源的可见交易文字；交易成功后直接记账，失败、取消和付款方式选择不入账。", fontSize = 14.sp)
            Text("付款账户可在支付前暂存并关联同次成功结果；未识别到时保留未指定账户。不保存页面原文，不执行点击或付款。", fontSize = 13.sp)
            Text("页面或版本变化可能造成漏记，真实微信、支付宝覆盖仍需验证。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
