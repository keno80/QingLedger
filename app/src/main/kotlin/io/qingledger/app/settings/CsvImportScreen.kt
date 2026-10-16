package io.qingledger.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.designsystem.component.*
import io.qingledger.designsystem.theme.LedgerColors
import io.qingledger.domain.importing.*
import io.qingledger.domain.model.TransactionKind
import java.math.BigDecimal
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun LazyListScope.csvImportPage(state: CsvImportState, viewModel: CsvImportViewModel) {
    item("csv-file") { CsvImportFile(state, viewModel) }
    val plan = state.plan ?: return
    if (plan.mappings.isNotEmpty()) {
        item("csv-mapping-title") { Text("分类与账户", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        items(plan.mappings, key = { "mapping-${it.key}" }) { mapping -> CsvMappingRow(mapping, plan, state.busy, viewModel) }
    }
    item("csv-confirm") { CsvImportConfirm(state, viewModel) }
    item("csv-rows-title") { Text("账单预览 · ${plan.file.totalRows} 笔", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    items(plan.rows, key = { "csv-row-${it.line}" }) { row ->
        SettingsSection {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val record = row.record
                Text(if (record == null) (if (row.status == CsvImportStatus.SKIPPED) "跳过记录" else "无效账单") else "${record.kind.label} · ${record.merchant.ifBlank { record.category.ifBlank { "账单" } }} · ¥${BigDecimal.valueOf(record.amountMinor, 2).toPlainString()}", fontSize = 14.sp)
                Text("第 ${row.line} 行 · ${row.message}", fontSize = 12.sp,
                    color = if (row.status == CsvImportStatus.INVALID) LedgerColors.expense else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (record?.kind == TransactionKind.REFUND) {
                    Text(if (record.originalExpenseId == null) "未关联原单 · 按所选分类冲减支出" else "已关联原支出", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (record.originalExpenseId != null) Button(onClick = { viewModel.unlinkRefund(record.id) }, enabled = !state.busy) { Text("解除关联") }
                }
            }
        }
    }
}

@Composable
private fun CsvImportFile(state: CsvImportState, viewModel: CsvImportViewModel) {
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.load(uri) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsSection {
            SettingsNavigationRow(state.source.title, "${state.source.hint} · 最大 16MB / 50000 笔", LedgerIcons.Upload,
                { open.launch(arrayOf("*/*")) }, enabled = !state.busy)
        }
        state.plan?.let { plan ->
            SettingsSection {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("可导入 ${plan.ready} 笔 · 重复 ${plan.duplicates} 笔", fontSize = 16.sp)
                    Text("待匹配 ${plan.pending} 笔 · 无效 ${plan.invalid} 笔", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (plan.skipped > 0) Text("按交易状态跳过 ${plan.skipped} 笔", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        state.result?.let { StatusCard("导入完成", "已导入 ${it.imported} 笔 · 跳过 ${it.skipped} 笔 · 无效 ${it.failed} 笔") }
        if (state.busy) Text("正在处理…", fontSize = 13.sp)
        state.error?.let { StatusCard("导入提示", it) }
    }
}

@Composable
private fun CsvImportConfirm(state: CsvImportState, viewModel: CsvImportViewModel) {
    val plan = state.plan ?: return
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::refresh, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("重新预览") }
            Button(onClick = { confirm = true }, enabled = !state.busy && plan.canImport, modifier = Modifier.weight(1f), colors = if (plan.canImport && !state.busy) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text("导入 ${plan.ready} 笔") }
        }
    }
    LedgerBottomSheet(confirm, "确认导入", { if (!state.busy) confirm = false }, allowDismiss = !state.busy) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("导入 ${plan.ready} 笔账单。")
            val skipped = listOfNotNull(if (plan.duplicates > 0) "重复 ${plan.duplicates} 笔" else null,
                if (plan.skipped > 0) "按状态 ${plan.skipped} 笔" else null, if (plan.invalid > 0) "无效 ${plan.invalid} 笔" else null)
            if (skipped.isNotEmpty()) Text("跳过：${skipped.joinToString("、")}。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("现有账单不会被覆盖；新增或恢复的分类与账户将在确认后保存。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { confirm = false }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { confirm = false; viewModel.commit() }, enabled = !state.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认导入") }
            }
        }
    }
}

@Composable
private fun CsvMappingRow(mapping: CsvImportMapping, plan: CsvImportPlan, busy: Boolean, viewModel: CsvImportViewModel) {
    var show by rememberSaveable(mapping.key) { mutableStateOf(false) }
    val categoryKind = if (mapping.type == CsvReferenceType.INCOME_CATEGORY) TransactionKind.INCOME else TransactionKind.EXPENSE
    val options = if (mapping.type == CsvReferenceType.ACCOUNT) plan.accounts.map { Triple(it.id, it.name, it.archived) }
        else plan.categories.filter { it.kind == categoryKind }.map { Triple(it.id, it.name, it.archived) }
    val typeLabel = when (mapping.type) { CsvReferenceType.ACCOUNT -> "账户"; CsvReferenceType.EXPENSE_CATEGORY -> "支出分类"; else -> "收入分类" }
    val current = when (mapping.choice.action) {
        CsvMappingAction.UNRESOLVED -> "请选择"
        CsvMappingAction.NONE -> "不设置"
        CsvMappingAction.CREATE -> "新增「${mapping.name}」"
        CsvMappingAction.RESTORE -> "恢复「${options.find { it.first == mapping.choice.id }?.second.orEmpty()}」"
        else -> options.find { it.first == mapping.choice.id }?.second ?: "请选择"
    }
    SettingsSection {
        SettingsNavigationRow(mapping.name, "$typeLabel · ${mapping.error ?: current}", if (mapping.type == CsvReferenceType.ACCOUNT) LedgerIcons.Wallet else LedgerIcons.Other,
            { show = true }, enabled = !busy)
    }
    fun select(choice: CsvMappingChoice) { show = false; viewModel.choose(mapping.key, choice) }
    LedgerBottomSheet(show, "匹配$typeLabel", { show = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!mapping.required) item { Button(onClick = { select(CsvMappingChoice(CsvMappingAction.NONE)) }, modifier = Modifier.fillMaxWidth()) { Text("不设置") } }
            if (options.none { it.second.equals(mapping.name, true) }) item {
                Button(onClick = { select(CsvMappingChoice(CsvMappingAction.CREATE)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("新增「${mapping.name}」") }
            }
            items(options, key = { it.first }) { (id, name, archived) ->
                Button(onClick = { select(CsvMappingChoice(if (archived) CsvMappingAction.RESTORE else CsvMappingAction.EXISTING, id)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (archived) "恢复并使用：$name" else name)
                }
            }
        }
    }
}
