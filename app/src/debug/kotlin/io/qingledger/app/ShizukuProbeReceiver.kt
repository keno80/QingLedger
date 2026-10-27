package io.qingledger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Debug shell diagnostics only. Does not request, grant or change any permission. */
class ShizukuProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val output = JSONObject()
        fun read(key: String, value: () -> Any?) {
            try { output.put(key, value() ?: JSONObject.NULL) }
            catch (error: Exception) { output.put(key, error.javaClass.simpleName + ": " + error.message) }
        }
        read("binderAlive") { Shizuku.pingBinder() }
        read("serverVersion") { Shizuku.getVersion() }
        read("serverUid") { Shizuku.getUid() }
        read("apiPermission") { Shizuku.checkSelfPermission() }
        read("livePermission") { IShizukuService.Stub.asInterface(Shizuku.getBinder()).checkSelfPermission() }
        read("androidPermission") { context.checkSelfPermission("moe.shizuku.manager.permission.API_V23") }
        read("declaredSupport") {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT")
        }
        read("controllerState") { (context.applicationContext as QingLedgerApplication).container.shizuku.status.value.state.name }
        read("controllerDetail") { (context.applicationContext as QingLedgerApplication).container.shizuku.status.value.detail }
        resultCode = 1
        resultData = output.toString()
    }
}
