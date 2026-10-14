package io.qingledger.app

import android.content.Context
import io.qingledger.data.repository.DataStoreSettingsRepository
import io.qingledger.domain.repository.SettingsRepository
import io.qingledger.data.database.LedgerDatabase
import io.qingledger.data.repository.RoomLedgerRepository
import io.qingledger.domain.repository.LedgerRepository
import io.qingledger.domain.repository.CaptureRepository
import io.qingledger.data.repository.RoomCaptureRepository
import io.qingledger.automation.notification.NotificationCaptureRuntime

class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context)
    private val database = LedgerDatabase.create(context)
    val ledgerRepository: LedgerRepository = RoomLedgerRepository(database)
    private val accessibilitySources = setOf("com.eg.android.AlipayGphone", "com.tencent.mm") + if (BuildConfig.DEBUG) setOf("io.qingledger.paymentsimulator") else emptySet()
    private val captureSources = accessibilitySources + if (BuildConfig.DEBUG) setOf("com.android.shell") else emptySet()
    val captureRepository: CaptureRepository = RoomCaptureRepository(database, ledgerRepository, captureSources)
    val captureRuntime = NotificationCaptureRuntime(context, captureRepository, accessibilitySources)
}
