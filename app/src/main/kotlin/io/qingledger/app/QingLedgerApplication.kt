package io.qingledger.app

import android.app.Application
import io.qingledger.automation.notification.CaptureHost

class QingLedgerApplication : Application(), CaptureHost {
    val container: AppContainer by lazy { AppContainer(this) }
    override val captureRuntime get() = container.captureRuntime
    override fun onCreate() {
        super.onCreate(); container
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { container.confirmations.activityStarted() }
            override fun onActivityStopped(activity: android.app.Activity) { container.confirmations.activityStopped() }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
