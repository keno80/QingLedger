package io.qingledger.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import io.qingledger.app.settings.MyScreen
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.qingledger.app.automationui.*
import io.qingledger.app.ledger.*
import io.qingledger.domain.model.LedgerTransaction
import io.qingledger.designsystem.component.StatusCard
import io.qingledger.app.settings.SettingsScreen
import io.qingledger.app.settings.ReferenceManagementScreen
import io.qingledger.app.statistics.StatisticsScreen
import io.qingledger.designsystem.component.LedgerIcons
import io.qingledger.domain.model.ThemeMode
import io.qingledger.domain.repository.SettingsRepository
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppNavHost(themeMode: ThemeMode, settingsRepository: SettingsRepository, ledger: LedgerViewModel, searchLedger: LedgerViewModel, capture: CaptureViewModel) {
    var savedRoute by rememberSaveable { mutableStateOf(AppRoute.LEDGER.name) }
    val route = AppRoute.fromSavedValue(savedRoute)
    val stateHolder = rememberSaveableStateHolder()
    val state by ledger.state.collectAsStateWithLifecycle()
    val searchState by searchLedger.state.collectAsStateWithLifecycle()
    val message by ledger.message.collectAsStateWithLifecycle()
    val busy by ledger.busy.collectAsStateWithLifecycle()
    val captureBusy by capture.busy.collectAsStateWithLifecycle()
    val candidateRows by capture.candidates.collectAsStateWithLifecycle()
    val undo by ledger.undo.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("list") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var session by rememberSaveable { mutableStateOf(0) }
    var referenceParent by rememberSaveable { mutableStateOf("list") }
    var transactionParent by rememberSaveable { mutableStateOf("list") }
    val selected by produceState<Pair<Boolean, LedgerTransaction?>>(true to null, selectedId, session) {
        value = true to null
        value = if (selectedId == null) false to null else try { false to ledger.get(selectedId!!) } catch (_: Exception) { false to null }
    }
    fun closePage() {
        if (screen != "list") stateHolder.removeState(if (screen == "search") "ledger-search" else "$screen-$selectedId-$session")
        screen = when (screen) { "candidate" -> "automation"; "automation" -> "preferences"; "categories", "accounts" -> referenceParent; "detail", "edit" -> transactionParent; else -> "list" }
        selectedId = null
    }
    fun openEditor(id: String?) { if (id == null) transactionParent = "list"; selectedId = id; session++; screen = "edit"; ledger.clearMessage() }
    fun openTransaction(id: String, parent: String) { transactionParent = parent; selectedId = id; session++; screen = "detail"; ledger.clearMessage() }

    BackHandler(enabled = screen != "list" || route != AppRoute.LEDGER) {
        if (!busy && !captureBusy) { if (screen != "list") closePage() else savedRoute = AppRoute.LEDGER.name }
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            message?.let { text ->
                Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (undo != null) Button(onClick = ledger::undoDelete, enabled = !busy) { Text("撤销删除") }
                        Button(onClick = ledger::clearMessage) { Text("关闭提示") }
                    }
                }
            }
        },
    ) { insets ->
        val showBottomBar = screen == "list" && route != AppRoute.AUTOMATION
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
        stateHolder.SaveableStateProvider(when (screen) { "list" -> route.name; "search" -> "ledger-search"; else -> "$screen-$selectedId-$session" }) {
            val pageState = rememberLazyListState()
            val pageScope = rememberCoroutineScope()
            LazyColumn(
                state = pageState,
                modifier = Modifier.fillMaxSize(),
                // Scroll content behind the floating controls; only the final item needs clearance.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (showBottomBar) 132.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (screen == "search" || (screen == "list" && route == AppRoute.LEDGER)) 0.dp else 16.dp),
            ) {
                if (screen != "list" || route != AppRoute.LEDGER) item(key = "header") {
                    Column(Modifier.padding(bottom = if (screen == "search") 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (screen != "list") IconButton(onClick = { if (!busy && !captureBusy) closePage() }, modifier = Modifier.size(44.dp)) { Icon(LedgerIcons.Back, "返回", modifier = Modifier.size(22.dp)) }
                            Text(when (screen) { "search" -> "搜索账单"; "preferences" -> "设置"; "automation" -> "自动记账"; "edit" -> if (selectedId == null) "记一笔" else "编辑账单"; "detail" -> "账单详情"; "candidate" -> "核对交易事件"; "categories" -> "分类管理"; "accounts" -> "账户管理"; else -> route.title }, fontSize = if (screen == "list") 24.sp else 20.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                when {
                    screen == "search" -> ledgerSearchPage(searchState, searchLedger) { openTransaction(it, "search") }
                    screen == "candidate" -> item {
                        candidateRows.find { it.id == selectedId }?.let { CandidateScreen(it, state, capture, captureBusy, ::closePage) }
                            ?: Button(onClick = ::closePage) { Text("事件不可用，返回自动") }
                        val captureMessage by capture.message.collectAsStateWithLifecycle()
                        captureMessage?.let { StatusCard("事件处理提示", it) }
                    }
                    screen != "list" && selectedId != null && selected.first -> item { StatusCard("正在读取账单", "请稍候。") }
                    screen != "list" && selectedId != null && selected.second == null -> item { Button(onClick = ::closePage) { Text("账单不可用，返回列表") } }
                    screen == "edit" -> item { TransactionEditor(state, selected.second, busy, { ledger.save(it, selected.second, ::closePage) }, ::closePage) }
                    screen == "detail" -> item { selected.second?.let { row ->
                        val history by androidx.compose.runtime.remember(row.id) { ledger.changes(row.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                        TransactionDetailScreen(row, state, busy, { openEditor(row.id) }, { ledger.delete(row, ::closePage) }, history)
                    } }
                    screen == "categories" || screen == "accounts" -> item { ReferenceManagementScreen(state, ledger, screen == "categories", busy, ::closePage, { pageScope.launch { pageState.animateScrollToItem(0) } }) }
                    screen == "preferences" -> item { SettingsScreen(themeMode, settingsRepository, { referenceParent = "preferences"; session++; screen = "categories"; ledger.clearMessage() }, { referenceParent = "preferences"; session++; screen = "accounts"; ledger.clearMessage() }, { session++; screen = "automation" }) }
                    screen == "automation" || route == AppRoute.AUTOMATION -> {
                        item("capture-controls") { AutomationScreen(capture) }
                        captureEvents(candidateRows, capture) { selectedId = it; session++; screen = "candidate"; capture.clearMessage() }
                    }
                    route == AppRoute.LEDGER -> ledgerPage(state, ledger, { searchLedger.resetSearch(state.selection.month); session++; screen = "search" }, { openTransaction(it, "list") })
                    route == AppRoute.STATISTICS -> item { StatisticsScreen(state, ledger) }
                    else -> item { MyScreen({ referenceParent = "list"; session++; screen = "categories" }, { referenceParent = "list"; session++; screen = "accounts" }, { session++; screen = "preferences" }) }
                }
            }
        }
        if (showBottomBar) Box(Modifier.align(Alignment.BottomCenter)) {
            AppBottomBar(
                route = route,
                onSelect = { savedRoute = it.name },
                onAdd = { if (!state.loading && !busy) openEditor(null) },
            )
        }
        }
    }
}
