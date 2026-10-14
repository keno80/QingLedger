package io.qingledger.automation.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class LedgerNotificationListener : NotificationListenerService() {
    private val runtime get() = (application as? CaptureHost)?.captureRuntime
    override fun onListenerConnected() { runtime?.connectionChanged(true) }
    override fun onListenerDisconnected() { runtime?.connectionChanged(false) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { if (sbn != null) runtime?.posted(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { if (sbn != null) runtime?.removed(sbn) }
    override fun onDestroy() { runtime?.connectionChanged(false); super.onDestroy() }
}
