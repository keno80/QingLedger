package io.qingledger.app.captureconfirmation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.qingledger.app.AppContainer
import io.qingledger.app.QingLedgerApplication
import io.qingledger.designsystem.theme.LedgerTheme
import io.qingledger.domain.model.ThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ConfirmationActivity : ComponentActivity() {
    private var candidateId: String? = null
    private val container get() = (application as QingLedgerApplication).container
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(ConfirmationCoordinator.EXTRA_CANDIDATE_ID)?.takeIf { it.length in 1..128 }
        if (id == null) { finish(); return }
        if (!container.confirmations.presentInActivity(id)) { finish(); return }
        candidateId = id
        setContent {
            BackHandler { if (!container.confirmations.busy.value) container.confirmations.ignore(id, ::finish) }
            ConfirmationWindow(container, id, ::finish)
        }
    }
    override fun onDestroy() {
        if (!isChangingConfigurations) candidateId?.let { container.confirmations.closedWithoutResolution(it) }
        super.onDestroy()
    }
}

@Composable
internal fun ConfirmationWindow(container: AppContainer, id: String, done: () -> Unit) {
    val mode by container.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; else -> false }
    val loaded by produceState<Pair<Boolean, io.qingledger.domain.model.PendingCandidate?>>(true to null, id) {
        container.captureRepository.candidate(id).collect { value = false to it }
    }
    val candidate = loaded.second
    val categories by container.ledgerRepository.categories.collectAsState(initial = emptyList())
    val accounts by container.ledgerRepository.accounts.collectAsState(initial = emptyList())
    val expenses by remember { container.ledgerRepository.statisticsTransactions(null) }.collectAsState(initial = emptyList())
    val busy by container.confirmations.busy.collectAsState()
    val failure by container.confirmations.failure.collectAsState()
    LaunchedEffect(loaded) { if (!loaded.first && candidate?.state != "PENDING") done() }
    LedgerTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).clickable(enabled = !busy) { container.confirmations.ignore(id, done) })
            val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .92f
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = maxHeight)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MiuixTheme.colorScheme.background)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}.navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(42.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(Color.Gray.copy(alpha = .35f)))
                Spacer(Modifier.height(12.dp))
                candidate?.takeIf { it.state == "PENDING" }?.let { row ->
                    ConfirmationContent(row, categories, accounts, expenses, busy, failure,
                        { container.confirmations.ignore(id, done) }, { draft, recognized -> container.confirmations.confirm(id, draft, recognized, done) })
                }
            }
        }
    }
}
