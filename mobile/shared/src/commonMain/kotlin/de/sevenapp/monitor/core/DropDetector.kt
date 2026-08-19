package de.sevenapp.monitor.core

/**
 * Turns a stream of probe results into drop events.
 *
 * A drop is defined structurally, as in the web app: we are inside a drop if
 * and only if the current run of consecutive failures has reached
 * [thresholdConsecutive]. Nothing tracks "am I in a drop" separately, so the
 * two representations cannot disagree.
 *
 * One deliberate improvement over the web app: it backdated a drop's start by
 * multiplying the failure count by the *nominal* probe interval, because a
 * browser probe loop had no record of when each failure actually happened.
 * Here every sample carries its own timestamp, so the drop is backdated to the
 * real first failure. On mobile that difference is large — background probes
 * are scheduled by the OS and can land minutes from their nominal slot, so the
 * estimate would have been consistently wrong in a way that under-reports
 * downtime.
 *
 * Not thread-safe; feed it from one place.
 */
class DropDetector(
    private val thresholdConsecutive: Int = DEFAULT_THRESHOLD,
) {
    init {
        require(thresholdConsecutive >= 1) { "threshold must be >= 1" }
    }

    private val closed = mutableListOf<DropEvent>()
    private var consecutiveFailures = 0

    /** Timestamp of the first failure in the current run, or null if the last probe succeeded. */
    private var runStartedAtEpochMs: Long? = null

    /** Non-null exactly while the current failure run has crossed the threshold. */
    private var openDropStartedAtEpochMs: Long? = null

    val isDropOpen: Boolean get() = openDropStartedAtEpochMs != null

    /**
     * @return an event describing what this sample changed, for logging/UI.
     */
    fun onSample(sample: PingSample): Transition {
        return if (sample.ok) onSuccess(sample.atEpochMs) else onFailure(sample.atEpochMs)
    }

    private fun onFailure(atEpochMs: Long): Transition {
        consecutiveFailures++
        if (runStartedAtEpochMs == null) runStartedAtEpochMs = atEpochMs

        if (consecutiveFailures >= thresholdConsecutive && openDropStartedAtEpochMs == null) {
            // Backdate to the first failure of this run, not this Nth one — the
            // connection was already down then, we just hadn't confirmed it.
            openDropStartedAtEpochMs = runStartedAtEpochMs
            return Transition.DropStarted(openDropStartedAtEpochMs!!)
        }
        return Transition.None
    }

    private fun onSuccess(atEpochMs: Long): Transition {
        val wasOpen = openDropStartedAtEpochMs
        consecutiveFailures = 0
        runStartedAtEpochMs = null
        openDropStartedAtEpochMs = null

        if (wasOpen != null) {
            val event = DropEvent(startedAtEpochMs = wasOpen, endedAtEpochMs = atEpochMs)
            closed += event
            return Transition.DropEnded(event)
        }
        return Transition.None
    }

    /**
     * All drops, including the currently-open one (with a null end) if there is
     * one. Callers computing uptime must include the open drop or they will
     * report a connection that is down *right now* as having perfect uptime.
     */
    fun allDrops(): List<DropEvent> =
        closed + listOfNotNull(openDropStartedAtEpochMs?.let { DropEvent(it, null) })

    /** Restores state across process death — background workers are not long-lived. */
    fun restore(closedDrops: List<DropEvent>, openDropStartedAtEpochMs: Long?, consecutiveFailures: Int) {
        closed.clear()
        closed += closedDrops
        this.openDropStartedAtEpochMs = openDropStartedAtEpochMs
        this.consecutiveFailures = consecutiveFailures
        this.runStartedAtEpochMs = openDropStartedAtEpochMs
    }

    sealed interface Transition {
        data object None : Transition
        data class DropStarted(val atEpochMs: Long) : Transition
        data class DropEnded(val event: DropEvent) : Transition
    }

    companion object {
        /** Two consecutive misses, matching the web app. One is too trigger-happy. */
        const val DEFAULT_THRESHOLD = 2
    }
}
