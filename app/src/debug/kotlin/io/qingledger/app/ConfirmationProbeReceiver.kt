package io.qingledger.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import io.qingledger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/** Shell-only fixture, additionally restricted to the separately installed QA package. */
class ConfirmationProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG || context.packageName != "io.qingledger.app.qa") return
        val token = intent.getStringExtra("fixture")?.takeIf { it.matches(Regex("[a-z0-9-]{1,32}")) } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as QingLedgerApplication).container
                withTimeout(5000) { while (!context.getSharedPreferences("capture_method", Context.MODE_PRIVATE).contains("method")) delay(20) }
                val source = "io.qingledger.paymentsimulator"
                container.ledgerRepository.initialize()
                container.captureRepository.setSource(source, true)
                container.captureRepository.setBookingMode(CaptureBookingMode.CONFIRM)
                container.captureRepository.setEnabled(true)
                val config = container.captureRepository.settings.first()
                fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                val event = CapturedNotification(source, Process.myUserHandle().toString(), "a11y:" + hash("qa:$token"), hash("qa-receipt:$token"), System.currentTimeMillis(), null, config.generation)
                val proposal = NotificationProposal(1888, TransactionKind.EXPENSE, "UNVERIFIED_TEMPLATE", "验证银行储蓄卡（1234）", "验证商户", 2000, 112)
                container.captureRepository.capture(event, proposal)
                val id = container.captureRepository.capturedCandidateId(event)
                if (id != null && container.captureRepository.candidate(id).first()?.state == "PENDING") container.confirmations.offer(id)
                pending.resultCode = 1
            } catch (_: Exception) { pending.resultCode = -1 }
            finally { pending.finish() }
        }
    }
}
