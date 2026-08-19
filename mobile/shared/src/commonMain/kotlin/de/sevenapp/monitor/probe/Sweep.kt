package de.sevenapp.monitor.probe

/**
 * One rung of the chunk-size ladder: try [bytes] this many [trials] times.
 */
data class SweepStep(val bytes: Int, val trials: Int)

/** Outcome of one rung, aggregated across its trials. */
data class SweepResult(
    val bytes: Int,
    val trials: Int,
    val passCount: Int,
    val avgDurationMs: Double?,
    val lastError: FailureReason?,
) {
    val ok: Boolean get() = passCount == trials
}

/**
 * The chunk-size sweep asks a different question from the throughput test:
 * not "how fast" but "does behaviour change with transfer size".
 *
 * A sharp pass/fail cutoff at one size, repeated across runs, is the signature
 * of size-based dropping. Noisy failures spread across all sizes point at
 * general link badness instead. Collapsing this into an aggregate number would
 * destroy the one thing that matters — *which* sizes — so results are kept
 * per-rung.
 */
object SweepPlan {

    /**
     * Default ladder, matching the web app: small sizes get more trials because
     * they are cheap and noisy; large sizes get fewer because each attempt is
     * slow.
     */
    val DEFAULT: List<SweepStep> = listOf(
        SweepStep(32_000, 3),
        SweepStep(128_000, 3),
        SweepStep(512_000, 2),
        SweepStep(2_000_000, 2),
        SweepStep(5_000_000, 1),
        SweepStep(10_000_000, 1),
    )

    private val LINE = Regex("""^\s*(\d+)\s*([KMkm]?)\s*(?:[xX*]\s*(\d+))?\s*$""")

    /**
     * Parses "32K x3, 128K x3" (comma- or newline-separated).
     *
     * Unparsable entries are skipped rather than rejecting the whole plan, and
     * an empty result falls back to [DEFAULT] — the same "corrupt input, fall
     * back quietly" rule the web app uses for settings, so a typo cannot
     * silently disable the sweep entirely.
     */
    fun parse(text: String?): List<SweepStep> {
        val steps = (text ?: "")
            .split('\n', ',')
            .mapNotNull { line ->
                val m = LINE.find(line.trim()) ?: return@mapNotNull null
                val magnitude = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val bytes = when (m.groupValues[2].uppercase()) {
                    "K" -> magnitude * 1_000
                    "M" -> magnitude * 1_000_000
                    else -> magnitude
                }
                val trials = m.groupValues[3].toIntOrNull() ?: 1
                if (bytes !in 1..MAX_BYTES || trials !in 1..MAX_TRIALS) return@mapNotNull null
                SweepStep(bytes.toInt(), trials)
            }
        return steps.ifEmpty { DEFAULT }
    }

    fun format(steps: List<SweepStep>): String =
        steps.joinToString(", ") { step ->
            val size = when {
                step.bytes % 1_000_000 == 0 -> "${step.bytes / 1_000_000}M"
                step.bytes % 1_000 == 0 -> "${step.bytes / 1_000}K"
                else -> step.bytes.toString()
            }
            "$size x${step.trials}"
        }

    /** Total bytes one full pass of the ladder moves, for the data budget. */
    fun totalBytes(steps: List<SweepStep>): Long =
        steps.sumOf { it.bytes.toLong() * it.trials }

    const val MAX_BYTES = 50_000_000L
    const val MAX_TRIALS = 10
}

/**
 * Runs the ladder in one direction.
 *
 * Every attempt gets the same fixed timeout regardless of size. Scaling the
 * timeout to the size would let a merely-slow large transfer pass by being
 * given more benefit of the doubt, which weakens exactly the cutoff signal the
 * sweep exists to detect.
 */
class SweepRunner(private val transport: Transport) {

    enum class Direction { DOWN, UP }

    suspend fun run(
        steps: List<SweepStep>,
        direction: Direction,
        config: ProbeConfig,
        shouldContinue: () -> Boolean = { true },
    ): List<SweepResult> {
        val results = mutableListOf<SweepResult>()

        for (step in steps) {
            if (!shouldContinue()) break

            var passCount = 0
            var durationSum = 0.0
            var lastError: FailureReason? = null

            repeat(step.trials) {
                if (!shouldContinue()) return@repeat

                val result = when (direction) {
                    Direction.DOWN -> transport.download(
                        config.downUrl(step.bytes), step.bytes, config.transferTimeoutMs,
                    )
                    Direction.UP -> transport.upload(
                        config.upUrl, step.bytes, config.transferTimeoutMs,
                    )
                }

                when (result) {
                    is TransferResult.Ok -> { passCount++; durationSum += result.elapsedMs }
                    // A partial transfer is a FAILURE for the sweep's purposes.
                    // The sweep asks "does this size get through", and a
                    // truncated transfer did not — counting it as a pass would
                    // blur the very cutoff being looked for.
                    is TransferResult.Partial -> lastError = result.reason
                    is TransferResult.Failed -> lastError = result.reason
                }
            }

            results += SweepResult(
                bytes = step.bytes,
                trials = step.trials,
                passCount = passCount,
                avgDurationMs = if (passCount > 0) durationSum / passCount else null,
                lastError = if (passCount == step.trials) null else lastError,
            )
        }

        return results
    }
}

/**
 * Reads a sweep as evidence rather than leaving the interpretation to the user.
 *
 * This is the sweep's whole point — the grid of pass/fail is the raw evidence,
 * and this turns it into the claim it supports, stated at the strength the data
 * actually justifies.
 */
object SweepVerdict {

    sealed interface Verdict {
        /** Every rung passed. */
        data object AllPassed : Verdict

        /** Clean cutoff: everything at/below [lastGoodBytes] passed, everything above failed. */
        data class SizeCutoff(val lastGoodBytes: Int, val firstBadBytes: Int) : Verdict

        /** Failures scattered across sizes — looks like general link trouble, not size-based. */
        data class Scattered(val failedCount: Int, val totalCount: Int) : Verdict

        data object NoData : Verdict
    }

    fun of(results: List<SweepResult>): Verdict {
        if (results.isEmpty()) return Verdict.NoData
        if (results.all { it.ok }) return Verdict.AllPassed

        val sorted = results.sortedBy { it.bytes }
        val firstFailureIdx = sorted.indexOfFirst { !it.ok }

        // A genuine size cutoff means: everything below the boundary passed AND
        // everything from it upward failed. Anything else is scattered, and
        // saying "your ISP drops large packets" on scattered data would be
        // inventing a diagnosis the evidence does not support.
        val cleanCutoff = sorted.take(firstFailureIdx).all { it.ok } &&
            sorted.drop(firstFailureIdx).all { !it.ok }

        return if (cleanCutoff && firstFailureIdx > 0) {
            Verdict.SizeCutoff(
                lastGoodBytes = sorted[firstFailureIdx - 1].bytes,
                firstBadBytes = sorted[firstFailureIdx].bytes,
            )
        } else {
            Verdict.Scattered(sorted.count { !it.ok }, sorted.size)
        }
    }
}
