package de.sevenapp.monitor

import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.core.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A rung that runs out of time has still measured the link.
 *
 * This is the difference between "1 MB failed" and "1 MB moved 480 KB in 60s,
 * so this line runs at 64 kbit/s" — and the second is the entire point of the
 * app on a deliberately throttled connection.
 */
class SweepEvidenceTest {

    @Test
    fun aTimedOutRungStillReportsTheRateItAchieved() {
        val rung = SweepResult(
            bytes = 1_000_000,
            trials = 1,
            passCount = 0,
            avgDurationMs = null,
            lastError = FailureReason.TIMEOUT,
            trialOutcomes = listOf(false),
            bytesTransferred = 480_000,
            totalElapsedMs = 60_000.0,
        )
        assertTrue(rung.timedOut)
        val mbps = assertNotNull(rung.observedMbps)
        // 480,000 bytes in 60s = 64 kbit/s.
        assertTrue(mbps in 0.060..0.070, "expected ~0.064 Mbps, got $mbps")
    }

    @Test
    fun aRungThatMovedNothingIsNotATimeoutMeasurement() {
        val rung = SweepResult(
            bytes = 1_000_000,
            trials = 1,
            passCount = 0,
            avgDurationMs = null,
            lastError = FailureReason.TIMEOUT,
            trialOutcomes = listOf(false),
            bytesTransferred = 0,
            totalElapsedMs = 60_000.0,
        )
        assertFalse(rung.timedOut, "no bytes moved is a dead link, not a slow one")
        assertEquals(null, rung.observedMbps)
    }

    @Test
    fun truncationAndSlownessAreDifferentFindings() {
        // Both fail the rung, but they say opposite things about the link:
        // INCOMPLETE means something dropped or cut the transfer, TIMEOUT means
        // it was working and simply needed longer.
        assertTrue(FailureReason.INCOMPLETE != FailureReason.TIMEOUT)
        assertTrue(FailureReason.ACK_MISMATCH != FailureReason.INCOMPLETE)
    }

    @Test
    fun theMobileTimeoutDefaultIsLongEnoughToProveAThrottledLineWorks() {
        // 1 MB at 64 kbit/s takes ~125s. A default below that can only ever
        // report a working-but-throttled connection as a failure.
        val config = ProbeConfig()
        val secondsForOneMegabyteAt64Kbit = (1_000_000L * 8) / 64_000.0
        assertTrue(
            config.mobileSweepTimeoutMs / 1000.0 >= secondsForOneMegabyteAt64Kbit * 0.9,
            "mobile sweep timeout of ${config.mobileSweepTimeoutMs}ms cannot complete 1MB at 64 kbit/s",
        )
        assertTrue(config.mobileSweepTimeoutMs > config.wifiSweepTimeoutMs)
    }

    @Test
    fun theTimeoutSelectedFollowsTheConnection() {
        val config = ProbeConfig(wifiSweepTimeoutMs = 15_000, mobileSweepTimeoutMs = 90_000)
        assertEquals(90_000, config.sweepTimeoutMs(NetworkType.CELLULAR))
        assertEquals(15_000, config.sweepTimeoutMs(NetworkType.WIFI))
        // Anything that is not cellular is treated as the unmetered case.
        assertEquals(15_000, config.sweepTimeoutMs(NetworkType.ETHERNET))
    }

    @Test
    fun settingsOfferATimeoutLongEnoughForTheSlowestRealisticCase() {
        assertTrue(LiveTestConfig.SWEEP_TIMEOUT_OPTIONS_MS.max() >= 300_000)
        assertTrue(LiveTestConfig.SWEEP_TIMEOUT_OPTIONS_MS.min() <= 10_000)
    }
}
