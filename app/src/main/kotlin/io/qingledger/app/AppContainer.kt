package io.qingledger.app

import android.content.Context
import io.qingledger.data.repository.DataStoreSettingsRepository
import io.qingledger.domain.repository.SettingsRepository
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.data.repository.RoomLedgerRepository
import io.qingledger.data.repository.RoomCsvExportRepository
import io.qingledger.domain.repository.LedgerRepository
import io.qingledger.domain.repository.CaptureRepository
import io.qingledger.data.repository.RoomCaptureRepository
import io.qingledger.automation.notification.NotificationCaptureRuntime
import io.qingledger.data.backup.RoomBackupRepository
import io.qingledger.domain.repository.BackupPreferences
import kotlinx.coroutines.flow.first
import java.io.File

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context)
    private val database = LedgerDatabase.create(context)
    val ledgerRepository: LedgerRepository = RoomLedgerRepository(database)
    val csvExportRepository = RoomCsvExportRepository(database)
    val csvImportRepository = io.qingledger.data.repository.RoomCsvImportRepository(database)
    private val accessibilitySources = setOf("com.eg.android.AlipayGphone", "com.tencent.mm") + if (BuildConfig.DEBUG) setOf("io.qingledger.paymentsimulator") else emptySet()
    private val captureSources = accessibilitySources + if (BuildConfig.DEBUG) setOf("com.android.shell") else emptySet()
    val captureRepository: CaptureRepository = RoomCaptureRepository(database, ledgerRepository, captureSources)
    val captureRuntime = NotificationCaptureRuntime(context, captureRepository, accessibilitySources)
    val confirmations by lazy { io.qingledger.app.captureconfirmation.ConfirmationCoordinator(app, this) }
    private val shizukuProcessor by lazy { io.qingledger.automation.accessibility.ShizukuReceiptProcessor(app, captureRuntime) }
    val shizuku: io.qingledger.automation.shizuku.ShizukuCaptureController by lazy {
        io.qingledger.automation.shizuku.ShizukuCaptureController(app) { frame -> shizukuProcessor.process(frame, shizuku::isCurrent) }
    }
    init { captureRuntime.confirmationHandler = { confirmations.offer(it) } }
    val backupRepository = RoomBackupRepository(context, database, File(context.filesDir, "restore-recovery.qlbackup"),
        readPreferences = { BackupPreferences(settingsRepository.themeMode.first(), captureRuntime.method.value) },
        writePreferences = { captureRuntime.selectMethod(it.method); settingsRepository.setThemeMode(it.theme) })
}
