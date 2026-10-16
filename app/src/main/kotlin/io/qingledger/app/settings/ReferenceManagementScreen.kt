package io.qingledger.app.settings

import androidx.compose.runtime.Composable
import io.qingledger.app.ledger.LedgerUiState
import io.qingledger.app.ledger.LedgerViewModel

@Composable
fun ReferenceManagementScreen(state: LedgerUiState, ledger: LedgerViewModel, category: Boolean, busy: Boolean, onBack: () -> Unit, onFocusForm: () -> Unit, focusAccountId: String? = null) {
    if (category) CategoryManagementScreen(state, ledger, busy)
    else AccountManagementScreen(state, ledger, busy, focusAccountId)
}
