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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                val capture: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory(container.captureRepository, container.captureRuntime))
                val data: DataManagementViewModel = viewModel(factory = DataManagementViewModel.Factory(container.backupRepository, applicationContext.contentResolver))
                AppNavHost(themeMode = mode, settingsRepository = settings, ledger = ledger, searchLedger = searchLedger, capture = capture, data = data)
            }
        }
    }
}
