package io.qingledger.automation.summary

import android.app.Notification
import androidx.test.platform.app.InstrumentationRegistry
import io.qingledger.domain.model.CaptureSettings
import io.qingledger.domain.model.DailySummaryRules
import io.qingledger.domain.model.LedgerSummary
import io.qingledger.domain.model.PeriodRange
import io.qingledger.domain.repository.CaptureRepository
import io.qingledger.domain.repository.LedgerRepository
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DailySummaryControllerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-10-26T16:30:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))

    @Test fun summaryStillObservesTheLedgerWhileCaptureIsPausedAndReleasesOnClose() {
        val repositories = Repositories()
        val controller = DailySummaryController(context, repositories.ledger, repositories.capture, clock)
        try {
            controller.start()
            controller.start()
            await { repositories.summaries.subscriptionCount.value == 1 && repositories.pending.subscriptionCount.value == 1 }
            assertFalse(repositories.settings.value.enabled)
            assertEquals(DailySummaryRules.period(now, ZoneId.of("Asia/Shanghai")), repositories.periods.single())
            controller.close()
            await { repositories.summaries.subscriptionCount.value == 0 && repositories.pending.subscriptionCount.value == 0 && repositories.settings.subscriptionCount.value == 0 }
            controller.start()
            assertEquals(1, repositories.periods.size)
        } finally { controller.close() }
    }

    @Test fun summarySettingCancelsObserversAndCanBeReenabledIndependently() {
        val repositories = Repositories()
        repositories.settings.value = CaptureSettings(dailySummaryEnabled = false)
        val controller = DailySummaryController(context, repositories.ledger, repositories.capture, clock)
        try {
            controller.start()
            await { repositories.settings.subscriptionCount.value == 1 }
            assertTrue(repositories.periods.isEmpty())
            repositories.settings.value = CaptureSettings(dailySummaryEnabled = true)
            await { repositories.summaries.subscriptionCount.value == 1 }
            repositories.settings.value = CaptureSettings(dailySummaryEnabled = false)
            await { repositories.summaries.subscriptionCount.value == 0 && repositories.pending.subscriptionCount.value == 0 }
            repositories.settings.value = CaptureSettings(dailySummaryEnabled = true)
            await { repositories.summaries.subscriptionCount.value == 1 && repositories.periods.size == 2 }
        } finally { controller.close() }
    }

    @Test fun restoreZoneChangeRecomputesDayRatherThanKeepingThePreviousRange() {
        val repositories = Repositories()
        val controller = DailySummaryController(context, repositories.ledger, repositories.capture, clock)
        try {
            controller.start()
            await { repositories.summaries.subscriptionCount.value == 1 }
            repositories.zone.set("America/New_York")
            repositories.summaries.value = LedgerSummary(income = 500, expense = 200)
            val expected = DailySummaryRules.period(now, ZoneId.of("America/New_York"))
            await { repositories.periods.lastOrNull() == expected && repositories.summaries.subscriptionCount.value == 1 }
            assertEquals(2, repositories.periods.size)
        } finally { controller.close() }
    }

    @Test fun clockRefreshAcrossMidnightReplacesTheOldDaySubscription() {
        val repositories = Repositories()
        val instant = AtomicReference(Instant.parse("2026-10-27T15:00:00Z"))
        val movingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = instant.get()
        }
        val controller = DailySummaryController(context, repositories.ledger, repositories.capture, movingClock)
        try {
            controller.start()
            await { repositories.summaries.subscriptionCount.value == 1 }
            val before = repositories.periods.single()
            instant.set(Instant.parse("2026-10-27T16:00:00Z"))
            controller.refresh()
            await { repositories.periods.lastOrNull()?.start == before.end && repositories.summaries.subscriptionCount.value == 1 }
            assertEquals(2, repositories.periods.size)
        } finally { controller.close() }
    }

    @Test fun servicePublisherReceivesFreshSummaryAndNullWhenDisabledOrClosed() {
        val repositories = Repositories()
        val published = CopyOnWriteArrayList<Notification?>()
        val controller = DailySummaryController(context, repositories.ledger, repositories.capture, clock, publish = { published.add(it) })
        try {
            controller.start()
            await { published.any { it != null } }
            repositories.summaries.value = LedgerSummary(income = 50_000, expense = 12_300)
            repositories.pending.value = 2
            await { published.lastOrNull()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "支出 ¥123.00 · 收入 ¥500.00 · 待确认 2 笔" }
            repositories.settings.value = CaptureSettings(dailySummaryEnabled = false)
            await { published.lastOrNull() == null && repositories.summaries.subscriptionCount.value == 0 }
            val beforeClose = published.size
            controller.close()
            assertEquals(beforeClose + 1, published.size)
            assertNull(published.last())
        } finally { controller.close() }
    }

    private fun await(predicate: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!predicate()) delay(10) }
    }

    /** Only the real Flow contracts used by the controller are supplied. */
    private class Repositories {
        val settings = MutableStateFlow(CaptureSettings())
        val summaries = MutableStateFlow(LedgerSummary())
        val pending = MutableStateFlow(0)
        val zone = AtomicReference("Asia/Shanghai")
        val periods = CopyOnWriteArrayList<PeriodRange>()
        val ledger: LedgerRepository = repository { method, arguments ->
            when (method.name) {
                "bookZoneId" -> zone.get()
                "summary" -> { periods += arguments!![0] as PeriodRange; summaries }
                else -> error("Unexpected ledger call: ${method.name}")
            }
        }
        val capture: CaptureRepository = repository { method, _ ->
            when (method.name) {
                "getSettings" -> settings
                "getPendingCount" -> pending
                else -> error("Unexpected capture call: ${method.name}")
            }
        }

        private inline fun <reified T> repository(crossinline invoke: (Method, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments -> invoke(method, arguments) } as T
    }
}
