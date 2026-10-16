package io.qingledger.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.BuildConfig
import io.qingledger.designsystem.component.LedgerBottomSheet
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.repository.LedgerOverview
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Layout follows the approved my() view in qingledger-ui-preview.html. */
@Composable
fun MyScreen(overview: LedgerOverview?, onCategories: () -> Unit, onAccounts: () -> Unit, onSettings: () -> Unit) {
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("我的", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { panel = "more" }, modifier = Modifier.size(40.dp).semantics { contentDescription = "更多操作" }) { Text("···", fontSize = 20.sp) }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(46.dp).background(LedgerColors.accentSurface, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                    Text("轻", fontSize = 23.sp, color = LedgerColors.accentContent)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("我的轻账", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("日常账本 · 数据保存在本机", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                MyMetric("已记录", overview?.recordedCount, "笔", Modifier.weight(1f))
                MyMetric("记账天数", overview?.recordedDays, "天", Modifier.weight(1f))
            }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(0.dp)) {
            MyLink("当前账本", "日常账本", LedgerIcons.Ledger) { panel = "book" }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MyQuick("资产与账户", LedgerIcons.Wallet, { panel = "assets" }, Modifier.weight(1f))
                MyQuick("分类管理", LedgerIcons.Other, onCategories, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MyQuick("数据管理", LedgerIcons.Database, { panel = "data" }, Modifier.weight(1f))
                MyQuick("设置", LedgerIcons.Gear, onSettings, Modifier.weight(1f))
            }
        }
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(0.dp)) {
            MyLink("关于轻账", "版本与更新记录", LedgerIcons.Entertainment) { panel = "about" }
        }
    }
    val title = when (panel) { "assets" -> "资产与账户"; "data" -> "数据管理"; "book" -> "当前账本"; "about" -> "关于轻账"; else -> "更多" }
    LedgerBottomSheet(panel != null, title, { panel = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (panel) {
                "assets" -> {
                    Text("资产余额统计暂未实现，可先管理资金账户。", fontSize = 14.sp)
                    Button(onClick = { panel = null; onAccounts() }, modifier = Modifier.fillMaxWidth()) { Text("管理账户") }
                }
                "data" -> Text("数据导入、导出和备份暂未实现，当前账单保存在本机。", fontSize = 14.sp)
                "book" -> Text("当前使用日常账本，暂不支持新增或切换账本。", fontSize = 14.sp)
                "about" -> {
                    Text("轻账 ${BuildConfig.VERSION_NAME}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("本地自动记账", fontSize = 14.sp)
                    Text("新增账本累计笔数与记账天数展示，优化“我的”页面布局。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                else -> {
                    Button(onClick = { panel = null; onSettings() }, modifier = Modifier.fillMaxWidth()) { Text("设置") }
                    Button(onClick = { panel = "about" }, modifier = Modifier.fillMaxWidth()) { Text("关于轻账") }
                }
            }
            Button(onClick = { panel = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("关闭") }
        }
    }
}

@Composable
private fun MyMetric(label: String, value: Int?, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value?.toString() ?: "—", fontSize = 20.sp)
            Text(unit, Modifier.padding(bottom = 2.dp), fontSize = 11.sp)
        }
    }
}

@Composable
private fun MyQuick(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Card(modifier, cornerRadius = 17.dp, insideMargin = PaddingValues(0.dp), onClick = onClick) {
        Row(Modifier.fillMaxWidth().heightIn(min = 53.dp).padding(horizontal = 6.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Text(title, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MyLink(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    BasicComponent(modifier = Modifier.fillMaxWidth().heightIn(min = 65.dp), insideMargin = PaddingValues(16.dp), onClick = onClick,
        startAction = {
            Box(Modifier.padding(end = 12.dp).size(34.dp).background(LedgerColors.accentSurface, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = LedgerColors.accentContent)
            }
        },
        endActions = { Icon(LedgerIcons.Next, null, modifier = Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
    ) {
        Text(title, fontSize = 14.sp)
        Spacer(Modifier.height(3.dp))
        Text(summary, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
