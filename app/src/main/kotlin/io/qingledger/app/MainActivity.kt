package io.qingledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.qingledger.app.ledger.LedgerViewModel
import io.qingledger.app.automationui.CaptureViewModel
import io.qingledger.app.settings.DataManagementViewModel
import io.qingledger.app.navigation.AppNavHost
import io.qingledger.designsystem.theme.LedgerTheme
import io.qingledger.domain.model.ThemeMode
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import android.content.Intent
import io.qingledger.app.automationui.AutomationForegroundService
import io.qingledger.app.captureconfirmation.*
import androidx.compose.runtime.mutableLongStateOf

class MainActivity : ComponentActivity() {
    private val todayRequest = mutableLongStateOf(0)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveTodayIntent() }
    private fun receiveTodayIntent() {
        if (intent.getBooleanExtra("open_today_ledger", false)) { todayRequest.longValue++; intent.removeExtra("open_today_ledger") }
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val config = (application as QingLedgerApplication).container.captureRepository.settings.first()
            if (config.dailySummaryEnabled || config.enabled) AutomationForegroundService.ensureStarted(this@MainActivity)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveTodayIntent()
        enableEdgeToEdge()
        val container = (application as QingLedgerApplication).container
        val settings = container.settingsRepository
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            LedgerTheme(darkTheme = dark) {
                val ledger: LedgerViewModel = viewModel(factory = LedgerViewModel.Factory(container.ledgerRepository))
                val searchLedger: LedgerViewModel = viewModel(key = "ledger-search", factory = LedgerViewModel.Factory(container.ledgerRepository))
                val statistics: io.qingledger.app.statistics.StatisticsViewModel = viewModel(factory = io.qingledger.app.statistics.StatisticsViewModel.Factory(container.ledgerRepository))
                val capture: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory(container.captureRepository, container.captureRuntime, container.ledgerRepository, container.shizuku,
                    { AutomationForegroundService.ensureStarted(this@MainActivity) }, { id -> startActivity(Intent(this@MainActivity, ConfirmationActivity::class.java).putExtra(ConfirmationCoordinator.EXTRA_CANDIDATE_ID, id)) }))
                val data: DataManagementViewModel = viewModel(factory = DataManagementViewModel.Factory(container.backupRepository, applicationContext.contentResolver, container.csvExportRepository))
                val csvImport: io.qingledger.app.settings.CsvImportViewModel = viewModel(factory = io.qingledger.app.settings.CsvImportViewModel.Factory(container.csvImportRepository, applicationContext.contentResolver))
                AppNavHost(themeMode = mode, settingsRepository = settings, ledger = ledger, searchLedger = searchLedger, statistics = statistics, capture = capture, data = data, csvImport = csvImport, todayRequest = todayRequest.longValue)
            }
        }
    }
}
