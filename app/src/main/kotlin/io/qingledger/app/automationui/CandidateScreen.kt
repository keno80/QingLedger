package io.qingledger.app.automationui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.qingledger.app.ledger.*
import io.qingledger.designsystem.component.*
import io.qingledger.domain.model.*
import top.yukonga.miuix.kmp.basic.*

@Composable
fun CandidateScreen(candidate: PendingCandidate, state: LedgerUiState, capture: CaptureViewModel, busy: Boolean, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusCard(sourceLabel(candidate.sourcePackage) + " · 待确认", reviewLabel(candidate.reason) + "。默认时间为通知接收时间，请核对；未保存原始通知全文。")
        if (candidate.state != "PENDING") {
            StatusCard("事件已处理", "不会再次创建账单。")
            Button(onClick = onDone) { Text("返回自动") }
        } else if (candidate.reason in setOf("REFUND_REVIEW", "UNSUPPORTED_CURRENCY")) {
            Button(onClick = { capture.ignore(candidate.id, onDone) }, enabled = !busy) { Text("忽略该事件") }
            Button(onClick = onDone) { Text("返回自动") }
        } else {
            if (candidate.relatedTransactionId != null) Button(onClick = { capture.merge(candidate, onDone) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("关联可能相同的账单") }
            val previous = LedgerTransaction(candidate.id, candidate.kind ?: TransactionKind.EXPENSE, candidate.amountMinor ?: 0, candidate.receivedAt, "", null, null, null, null, "", 0, state.zoneId)
            if (candidate.relatedTransactionId != null) Text("下面的保存操作将明确创建一笔新账单，请先排除重复。")
            TransactionEditor(state, previous, busy, { capture.confirm(candidate.id, it, onDone) }, onDone, requireKindSelection = candidate.kind == null, saveLabel = "确认并入账")
        }
    }
}
