package de.sevenapp.monitor.android

import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.probe.AutomaticTransfers
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.SweepFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The DATA-01 acceptance case, simulated end to end at the Android cadence.
 *
 * The worker itself needs a device to run, but the decision it delegates to is
 * pure — so "96 WorkManager wakes a day cannot exceed the stated budget" is
 * answerable here rather than only by watching a phone for 24 hours.
 */
class AutomaticTransfersBudgetTest {

    private val now = 1_700_000_000_000L

    @Test
    fun ninetySixWakesADayStayWithinTheStatedBudget() {
        val config = ProbeConfig(
            cycleIntervalMinutes = 15,
            automaticStreamEnabled = true,
            automaticSweepEnabled = true,
            sweepFrequency = SweepFrequency.HOURLY,
        )
        var meteredToday = 0L
        var lastSweepAt: Long? = null
        var runs = 0
        var sweepRuns = 0

        repeat(96) { i ->
            val wakeAt = now + i * 15 * 60_000L
            val decision = AutomaticTransfers.decide(
                config = config,
                deviceState = DeviceState(NetworkType.CELLULAR, isCharging = true, isMetered = true),
                usage = AutomaticTransfers.Usage(lastSweepAt, meteredToday, meteredToday),
                nowEpochMs = wakeAt,
            )
            if (decision is AutomaticTransfers.Decision.Run) {
                runs++
                // Worst case: every run spends its full reservation.
                meteredToday += decision.plan.maxBytesPerRun
                if (decision.plan.runSweep) {
                    sweepRuns++
                    lastSweepAt = wakeAt
                }
            }
        }

        assertTrue(runs > 0, "the budget must not block every run")
        assertTrue(
            meteredToday <= config.automaticDailyMeteredBytes,
            "spent ${meteredToday}B against a ${config.automaticDailyMeteredBytes}B daily budget",
        )
        // The metered byte budget binds first here (stream runs every wake and
        // exhausts it quickly), so this only has to hold as an upper bound —
        // the dedicated cadence test in :shared isolates the hourly gate on
        // its own and asserts the exact count.
        assertTrue(sweepRuns <= 24, "sweep ran more than once an hour: $sweepRuns times")
    }

    @Test
    fun anOfflineWakeSkipsTransfersWithoutSkippingTheCycle() {
        // MON-01's other half: the worker now runs while disconnected, so the
        // transfer decision — not the WorkManager constraint — is what must
        // refuse to put bytes on a network that is not there.
        val decision = AutomaticTransfers.decide(
            config = ProbeConfig(automaticStreamEnabled = true),
            deviceState = DeviceState(NetworkType.NONE, isCharging = true, isMetered = false),
            usage = AutomaticTransfers.Usage(null, 0, 0),
            nowEpochMs = now,
        )
        val skip = decision as AutomaticTransfers.Decision.Skip
        assertEquals("no network", skip.reason)
    }
}
