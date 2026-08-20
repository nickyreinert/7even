package de.sevenapp.monitor

import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.SweepPlan
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.SweepStep
import de.sevenapp.monitor.probe.SweepVerdict
import de.sevenapp.monitor.probe.TransferResult
import de.sevenapp.monitor.probe.Transport
import de.sevenapp.monitor.probe.TransportResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SweepPlanTest {

    @Test
    fun parsesSuffixedSizesAndTrials() {
        val plan = SweepPlan.parse("32K x3, 2M x2")
        assertEquals(listOf(SweepStep(32_000, 3), SweepStep(2_000_000, 2)), plan)
    }

    @Test
    fun parsesNewlineSeparated() {
        assertEquals(2, SweepPlan.parse("32K x1\n64K x1").size)
    }

    @Test
    fun trialsDefaultToOneWhenOmitted() {
        assertEquals(listOf(SweepStep(500_000, 1)), SweepPlan.parse("500K"))
    }

    @Test
    fun bareByteCountsWork() {
        assertEquals(listOf(SweepStep(4096, 2)), SweepPlan.parse("4096 x2"))
    }

    @Test
    fun garbageLinesAreSkippedNotFatal() {
        // "corrupt input, fall back quietly" — one bad line must not discard
        // the good ones alongside it.
        val plan = SweepPlan.parse("32K x2\nnonsense!!\n64K x1")
        assertEquals(listOf(SweepStep(32_000, 2), SweepStep(64_000, 1)), plan)
    }

    @Test
    fun entirelyUnparsableFallsBackToDefaultNotEmpty() {
        // An empty plan would silently disable the sweep — a typo should not be
        // able to turn off a whole measurement strategy.
        assertEquals(SweepPlan.DEFAULT, SweepPlan.parse("!!!"))
        assertEquals(SweepPlan.DEFAULT, SweepPlan.parse(""))
        assertEquals(SweepPlan.DEFAULT, SweepPlan.parse(null))
    }

    @Test
    fun oversizedOrAbsurdEntriesAreRejected() {
        assertEquals(SweepPlan.DEFAULT, SweepPlan.parse("999M x1"))
        assertEquals(SweepPlan.DEFAULT, SweepPlan.parse("1K x999"))
    }

    @Test
    fun formatRoundTrips() {
        val plan = listOf(SweepStep(32_000, 3), SweepStep(2_000_000, 2))
        assertEquals(plan, SweepPlan.parse(SweepPlan.format(plan)))
    }

    @Test
    fun totalBytesAccountsForTrials() {
        assertEquals(96_000L, SweepPlan.totalBytes(listOf(SweepStep(32_000, 3))))
    }
}

private class ScriptedTransport(
    private val onTransfer: (bytes: Int) -> TransferResult,
) : Transport {
    override suspend fun timedGet(url: String, timeoutMs: Long) = TransportResult.Ok(10.0)
    override suspend fun download(url: String, expectBytes: Int, timeoutMs: Long) = onTransfer(expectBytes)
    override suspend fun upload(url: String, bytes: Int, timeoutMs: Long) = onTransfer(bytes)
}

class SweepRunnerTest {

    private val steps = listOf(SweepStep(1000, 2), SweepStep(2000, 2), SweepStep(4000, 2))

    @Test
    fun allPassingProducesOkRungs() = runTest {
        val t = ScriptedTransport { TransferResult.Ok(it.toLong(), 50.0) }
        val results = SweepRunner(t).run(steps, SweepRunner.Direction.DOWN, ProbeConfig())
        assertEquals(3, results.size)
        assertTrue(results.all { it.ok })
        assertEquals(50.0, results.first().avgDurationMs)
    }

    @Test
    fun sizeCutoffShowsAsRungsFailingAboveAThreshold() = runTest {
        val t = ScriptedTransport { bytes ->
            if (bytes > 1500) TransferResult.Failed(FailureReason.TIMEOUT)
            else TransferResult.Ok(bytes.toLong(), 20.0)
        }
        val results = SweepRunner(t).run(steps, SweepRunner.Direction.DOWN, ProbeConfig())
        assertTrue(results[0].ok)
        assertTrue(!results[1].ok && results[1].passCount == 0)
        assertTrue(!results[2].ok)
    }

    @Test
    fun partialTransferCountsAsFailureNotPass() = runTest {
        // The sweep asks "does this size get through". A truncated transfer did
        // not, and counting it as a pass would blur the cutoff being looked for.
        val t = ScriptedTransport { TransferResult.Partial(it / 2L, 50.0, FailureReason.TIMEOUT) }
        val results = SweepRunner(t).run(steps, SweepRunner.Direction.DOWN, ProbeConfig())
        assertTrue(results.all { it.passCount == 0 })
        assertEquals(FailureReason.TIMEOUT, results.first().lastError)
    }

    @Test
    fun cancellationStopsTheLadderEarly() = runTest {
        var calls = 0
        val t = ScriptedTransport { calls++; TransferResult.Ok(it.toLong(), 10.0) }
        val results = SweepRunner(t).run(
            steps, SweepRunner.Direction.DOWN, ProbeConfig(), shouldContinue = { calls < 2 },
        )
        assertTrue(results.size < 3, "expected the sweep to stop early, got ${results.size} rungs")
    }
}

class SweepVerdictTest {

    private fun rung(bytes: Int, ok: Boolean) =
        SweepResult(bytes, 2, if (ok) 2 else 0, if (ok) 10.0 else null, null)

    @Test
    fun allPassing() {
        val v = SweepVerdict.of(listOf(rung(1000, true), rung(2000, true)))
        assertIs<SweepVerdict.Verdict.AllPassed>(v)
    }

    @Test
    fun cleanCutoffIsReportedWithItsBoundary() {
        val v = SweepVerdict.of(
            listOf(rung(1000, true), rung(2000, true), rung(4000, false), rung(8000, false)),
        )
        val cutoff = assertIs<SweepVerdict.Verdict.SizeCutoff>(v)
        assertEquals(2000, cutoff.lastGoodBytes)
        assertEquals(4000, cutoff.firstBadBytes)
    }

    @Test
    fun scatteredFailuresAreNotCalledACutoff() {
        // Claiming "your ISP drops large transfers" on scattered evidence would
        // be inventing a diagnosis the data does not support.
        val v = SweepVerdict.of(
            listOf(rung(1000, false), rung(2000, true), rung(4000, false), rung(8000, true)),
        )
        assertIs<SweepVerdict.Verdict.Scattered>(v)
    }

    @Test
    fun everythingFailingIsScatteredNotACutoffAtZero() {
        // No rung passed, so there is no "last good size" to name.
        val v = SweepVerdict.of(listOf(rung(1000, false), rung(2000, false)))
        assertIs<SweepVerdict.Verdict.Scattered>(v)
    }

    @Test
    fun emptyIsNoData() {
        assertIs<SweepVerdict.Verdict.NoData>(SweepVerdict.of(emptyList()))
    }

    @Test
    fun verdictIsOrderIndependent() {
        val shuffled = listOf(rung(8000, false), rung(1000, true), rung(4000, false), rung(2000, true))
        val cutoff = assertIs<SweepVerdict.Verdict.SizeCutoff>(SweepVerdict.of(shuffled))
        assertEquals(2000, cutoff.lastGoodBytes)
    }

    @Test
    fun aMixedRungIsNotAClaimOfASizeCutoff() {
        // 2 of 3 trials passing at 512K is evidence that 512K DOES get through
        // sometimes — the opposite of a hard size limit. `ok` collapsed it to
        // "failed", which let intermittent link trouble be diagnosed as
        // "your ISP drops anything over 128K".
        val results = listOf(
            SweepResult(32_000, 3, passCount = 3, avgDurationMs = 10.0, lastError = null),
            SweepResult(128_000, 3, passCount = 3, avgDurationMs = 20.0, lastError = null),
            SweepResult(512_000, 3, passCount = 2, avgDurationMs = 30.0, lastError = FailureReason.TIMEOUT),
        )
        assertEquals(SweepResult.Outcome.MIXED, results[2].outcome)
        assertIs<SweepVerdict.Verdict.Scattered>(SweepVerdict.of(results))
    }

    @Test
    fun aSustainedAllFailBoundaryIsStillACutoff() {
        val results = listOf(
            SweepResult(32_000, 3, passCount = 3, avgDurationMs = 10.0, lastError = null),
            SweepResult(128_000, 3, passCount = 3, avgDurationMs = 20.0, lastError = null),
            SweepResult(512_000, 2, passCount = 0, avgDurationMs = null, lastError = FailureReason.TIMEOUT),
            SweepResult(2_000_000, 1, passCount = 0, avgDurationMs = null, lastError = FailureReason.TIMEOUT),
        )
        val verdict = assertIs<SweepVerdict.Verdict.SizeCutoff>(SweepVerdict.of(results))
        assertEquals(128_000, verdict.lastGoodBytes)
        assertEquals(512_000, verdict.firstBadBytes)
    }

    @Test
    fun aCancelledRungProvesNothingAndIsExcluded() {
        val cancelled = SweepResult(
            bytes = 512_000, trials = 3, passCount = 0, avgDurationMs = null,
            lastError = null, trialOutcomes = emptyList(), cancelled = true,
        )
        assertEquals(SweepResult.Outcome.INCOMPLETE, cancelled.outcome)
        assertEquals(0, cancelled.attempted)

        val results = listOf(
            SweepResult(32_000, 3, passCount = 3, avgDurationMs = 10.0, lastError = null),
            cancelled,
        )
        // Everything that actually ran passed, so the only honest verdict is
        // "all passed" over the rungs that produced evidence.
        assertIs<SweepVerdict.Verdict.AllPassed>(SweepVerdict.of(results))
    }

    @Test
    fun cancellingMidLadderRecordsWhatWasAttemptedNotWhatWasPlanned() = runTest {
        // The old `return@repeat` skipped one trial and kept looping, so the
        // emitted result still claimed all three configured trials had run.
        var allowed = 2
        val transport = object : Transport {
            override suspend fun timedGet(url: String, timeoutMs: Long): TransportResult =
                TransportResult.Ok(10.0)
            override suspend fun download(url: String, expectBytes: Int, timeoutMs: Long): TransferResult {
                allowed--
                return TransferResult.Ok(expectBytes.toLong(), 10.0)
            }
            override suspend fun upload(url: String, bytes: Int, timeoutMs: Long): TransferResult =
                TransferResult.Ok(bytes.toLong(), 10.0)
        }
        val results = SweepRunner(transport).run(
            steps = listOf(SweepStep(32_000, 5)),
            direction = SweepRunner.Direction.DOWN,
            config = ProbeConfig(),
            shouldContinue = { allowed > 0 },
        )
        assertEquals(1, results.size)
        assertTrue(results[0].cancelled)
        assertEquals(2, results[0].attempted)
        assertEquals(5, results[0].trials)
        assertEquals(SweepResult.Outcome.INCOMPLETE, results[0].outcome)
    }
}
