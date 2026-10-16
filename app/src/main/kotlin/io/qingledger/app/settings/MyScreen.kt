package io.qingledger.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.BuildConfig
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.designsystem.theme.LedgerColors
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MyScreen(onCategories: () -> Unit, onAccounts: () -> Unit, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(50.dp).background(LedgerColors.accentSurface, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                    Icon(LedgerIcons.Ledger, null, modifier = Modifier.size(28.dp), tint = LedgerColors.accentContent)
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("我的轻账", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("本地记账，轻松记录每一天", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Spacer(Modifier.height(20.dp))
            MyDivider()
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("当前账本", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("日常账本", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MySectionTitle("账本管理")
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(0.dp)) {
                MyEntry("资金账户", "管理现金、零钱与银行卡", LedgerIcons.Housing, onAccounts)
                MyDivider(Modifier.padding(start = 68.dp, end = 16.dp))
                MyEntry("分类管理", "整理支出与收入分类", LedgerIcons.Other, onCategories)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MySectionTitle("应用")
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(0.dp)) {
                MyEntry("设置", "自动记账、外观与应用信息", LedgerIcons.Settings, onSettings)
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("数据保存在本机", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
            Text("轻账 ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun MyEntry(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    BasicComponent(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        startAction = {
            Box(Modifier.padding(end = 12.dp).size(36.dp).background(LedgerColors.accentSurface, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = LedgerColors.accentContent)
            }
        },
        endActions = { Icon(LedgerIcons.Next, null, modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun MySectionTitle(title: String) {
    Text(title, Modifier.padding(start = 4.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun MyDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)))
}
