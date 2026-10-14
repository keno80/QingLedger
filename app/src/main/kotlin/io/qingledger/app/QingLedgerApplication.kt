package io.qingledger.app

import android.app.Application
import io.qingledger.automation.notification.CaptureHost

class QingLedgerApplication : Application(), CaptureHost {
    val container: AppContainer by lazy { AppContainer(this) }
    override val captureRuntime get() = container.captureRuntime
    override fun onCreate() { super.onCreate(); container }
}
