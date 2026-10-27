package io.qingledger.automation.summary

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.qingledger.domain.model.DailySummaryRules
import io.qingledger.domain.model.LedgerSummary
import io.qingledger.domain.repository.CaptureRepository
import io.qingledger.domain.repository.LedgerRepository
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/** Optional publisher lets a foreground-service host own the same summary notification. */
class DailySummaryController(
    context: Context,
    private val ledger: LedgerRepository,
    private val capture: CaptureRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val publish: ((Notification?) -> Unit)? = null,
) : AutoCloseable {
    private val app = context.applicationContext
    private val notices = DailySummaryNotification(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var started = false
    private var closed = false
    private var receiverRegistered = false
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    @Synchronized fun start() {
        if (started || closed) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(dateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(dateReceiver, filter)
            receiverRegistered = true
        } catch (_: RuntimeException) { /* Midnight refresh still works if an OEM rejects receiver registration. */ }
        scope.launch {
            capture.settings.map { it.dailySummaryEnabled }.distinctUntilChanged()
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) false else { delay(RETRY_DELAY); true }
                }.collectLatest { enabled ->
                    if (!enabled) {
                        synchronized(this@DailySummaryController) { clearNotification() }
                    } else {
                        refreshes.onStart { emit(Unit) }.collectLatest {
                            try { observeToday() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                synchronized(this@DailySummaryController) { clearNotification() }
                                delay(RETRY_DELAY)
                                refresh()
                            }
                        }
                    }
                }
        }
    }

    fun refresh() { refreshes.tryEmit(Unit) }

    fun notificationsEnabled(): Boolean = notices.notificationsEnabled()

    private suspend fun observeToday(): Unit = coroutineScope {
        val zone = ZoneId.of(ledger.bookZoneId())
        val period = DailySummaryRules.period(clock.instant(), zone)
        launch {
            delay((period.end - clock.millis()).coerceAtLeast(1L))
            refresh()
        }
        // Query the complete DB aggregate, not the paginated transaction list. Room invalidation
        // covers edits, deletes, refunds, imports and restores as well as new transactions.
        combine(ledger.summary(period), capture.pendingCount) { summary, pending -> summary to pending }
            .collect { (summary, pending) ->
                // Restoring a book can change its zone without a system timezone broadcast.
                if (ledger.bookZoneId() != zone.id || DailySummaryRules.period(clock.instant(), zone) != period) {
                    refresh()
                } else synchronized(this@DailySummaryController) {
                    if (!closed) showNotification(summary, pending)
                }
            }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        if (receiverRegistered) {
            try { app.unregisterReceiver(dateReceiver) } catch (_: RuntimeException) { }
            receiverRegistered = false
        }
        clearNotification()
    }

    private fun showNotification(summary: LedgerSummary, pending: Int) {
        if (publish == null) notices.show(summary, pending)
        else try { publish.invoke(notices.notification(summary, pending)) }
        catch (_: RuntimeException) { /* Service/notification restrictions must not interrupt bookkeeping. */ }
    }

    private fun clearNotification() {
        if (publish == null) notices.cancel()
        else try { publish.invoke(null) } catch (_: RuntimeException) { }
    }

    private companion object { const val RETRY_DELAY = 1_000L }
}
