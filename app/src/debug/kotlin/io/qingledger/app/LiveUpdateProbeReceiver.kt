package io.qingledger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.qingledger.automation.notification.CaptureLiveNotification

/** Shell-only, Debug-only visual probe. Never reads pages or creates a transaction. */
class LiveUpdateProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val notices = CaptureLiveNotification(context.applicationContext, notificationId = 4102)
        notices.started(diagnostic = true)
        Handler(Looper.getMainLooper()).postDelayed({
            notices.cancel()
            pending.finish()
        }, 8_000)
    }
}
