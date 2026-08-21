package de.sevenapp.monitor.android.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sevenapp.monitor.android.billing.EntitlementRepository
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.android.livetest.LiveTestRunner
import de.sevenapp.monitor.android.net.NetworkBinder
import de.sevenapp.monitor.android.work.ProbeWorker
import de.sevenapp.monitor.android.work.ReportWorker
import de.sevenapp.monitor.android.work.LiveTestForegroundService
import de.sevenapp.monitor.android.work.MonitoringNotification
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.core.StabilityScore
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.probe.DataBudget
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.LivePhase
import de.sevenapp.monitor.probe.LiveSample
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.report.Report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** One human-readable line in the probe log — a phase change, a ping, a stream rate summary, or a sweep rung. */
data class LiveLogEntry(val atEpochMs: Long, val text: String)

data class DashboardState(
    val tier: Tier = Tier.FREE,
    val monitoringEnabled: Boolean = false,
    val intervalMinutes: Int = 15,
    /** Per-phase duration: ping, download stream, and upload stream each get this long. */
    val liveTestDurationMs: Long = LiveTestConfig.DEFAULT_PHASE_DURATION_MS,
    val monitoringNetworks: Set<NetworkType> = setOf(NetworkType.WIFI, NetworkType.CELLULAR),
    val manualNetwork: NetworkPreference = NetworkPreference.WIFI,
    /** Whether any Wi-Fi network is currently reachable — hides "Wi-Fi" as a manual-test choice when it plainly isn't. */
    val wifiAvailable: Boolean = true,
    val lightDownBytes: Int = 256 * 1024,
    val lightUpBytes: Int = 128 * 1024,
    val manualTestRunning: Boolean = false,
    /** Whether Settings has the sustained probe toggle on — shown as a heads-up next to the test button. */
    val sustainedProbeEnabled: Boolean = false,
    /** True once the current/most recent run reached its sustained-probe phase, so the chart/summary below know whose data they're showing. */
    val ranSustainedProbe: Boolean = false,
    /** Elapsed time within the *current phase*, which is what the countdown counts. */
    val manualTestElapsedMs: Long = 0L,
    val manualTestStep: Int = 0,
    val manualTestTotalSteps: Int = LiveTestConfig.PHASE_COUNT,
    val manualTestStepLabel: String = "Preparing",
    /** How long the current phase runs, or null when it is not time-bounded. */
    val manualTestPhaseDurationMs: Long? = null,
    val manualTestError: String? = null,
    val recentPings: List<PingSample> = emptyList(),
    val recentThroughput: List<ThroughputSample> = emptyList(),
    /**
     * Probes from this run's ping phase. Phases never overlap, so every one of
     * these was taken on an idle link.
     */
    val livePings: List<PingSample> = emptyList(),
    val liveDownloadMbps: List<Double> = emptyList(),
    val liveUploadMbps: List<Double> = emptyList(),
    val latestDownloadSweep: List<SweepResult> = emptyList(),
    val latestUploadSweep: List<SweepResult> = emptyList(),
    /** Every phase/ping/rate/sweep-rung event from the current or most recent manual run, oldest-first. */
    val liveLog: List<LiveLogEntry> = emptyList(),
    val latencyMedianMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val dropCount: Int = 0,
    val meteredBytesThisMonth: Long = 0,
    val projectedMeteredBytesPerMonth: Long = 0,
    val latestReport: Report? = null,
    /** A generated report the user has not acknowledged, delivered or not. */
    val pendingReport: RoomMonitorStore.Companion.PendingReport? = null,
    val stability: StabilityScore.Result? = null,
)

/**
 * "2/5 · Download stream · 00:07" — shared by the manual-test button label and
 * the foreground-service notification so the two surfaces can't drift apart.
 */
internal fun phaseProgressLabel(state: DashboardState): String {
    val phaseMs = state.manualTestPhaseDurationMs
    val displayedMs = if (phaseMs == null || phaseMs == Long.MAX_VALUE) {
        state.manualTestElapsedMs
    } else {
        (phaseMs - state.manualTestElapsedMs).coerceAtLeast(0L)
    }
    val seconds = displayedMs / 1_000
    val timer = "%02d:%02d".format(seconds / 60, seconds % 60)
    val step = if (state.manualTestStep <= 0) "" else "${state.manualTestStep}/${state.manualTestTotalSteps} · "
    return "$step${state.manualTestStepLabel} · $timer"
}

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RoomMonitorStore.get(app)
    private val entitlements = EntitlementRepository.get(app)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private var manualTestJob: Job? = null
    private var manualTimerJob: Job? = null
    private var manualRunGeneration = 0L

    /**
     * Tracks whether any Wi-Fi network is currently reachable, live — not just
     * at the moment [refresh] happened to run. A one-shot check at screen-open
     * wouldn't notice the user turning Wi-Fi off while already looking at this
     * screen, which is exactly when offering "test on Wi-Fi" as a live choice
     * is most misleading: it would just fail with "unavailable" instead of
     * never being offered.
     */
    private val wifiAvailabilityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = applyWifiAvailability(true)

        override fun onLost(network: Network) {
            // Re-checked rather than assumed false: onLost fires per-network,
            // and another Wi-Fi network could still be up.
            applyWifiAvailability(currentlyHasWifi())
        }
    }

    private fun applyWifiAvailability(available: Boolean) {
        _state.update {
            it.copy(
                wifiAvailable = available,
                // Otherwise the manual-network choice is left selected on a
                // now-disabled chip, and "Start monitoring" would just fail
                // with "Wi-Fi is unavailable" instead of the chip having
                // stopped offering it.
                manualNetwork = if (!available && it.manualNetwork == NetworkPreference.WIFI) {
                    NetworkPreference.CELLULAR
                } else {
                    it.manualNetwork
                },
            )
        }
    }

    init {
        refresh()
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm?.registerNetworkCallback(wifiRequest, wifiAvailabilityCallback) }
    }

    override fun onCleared() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        runCatching { cm?.unregisterNetworkCallback(wifiAvailabilityCallback) }
    }

    private fun currentlyHasWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val dayAgo = now - 24 * 60 * 60 * 1000
        val monthStart = now - 30L * 24 * 60 * 60 * 1000

        val config = store.loadConfig()
        val latestSweeps = store.loadLatestSweeps()
        val pings = store.pingsBetween(dayAgo, now)
        val rtts = pings.mapNotNull { it.rttMs }
        val failures = pings.count { !it.ok }

        val drops = store.dropsOverlapping(dayAgo, now)
        val lossPct = if (pings.isEmpty()) null else (failures.toDouble() / pings.size) * 100.0
        // Computed here, not left to the separately-registered NetworkCallback,
        // so app startup has one authoritative place setting the initial
        // manualNetwork/wifiAvailable pair instead of two async writers racing
        // to set it (the callback is only for live changes after this point).
        val wifiAvailable = currentlyHasWifi()
        val resolvedManualNetwork = config.preferredTestNetwork.let { if (it == NetworkPreference.AUTO) NetworkPreference.WIFI else it }
        _state.value = _state.value.copy(
            tier = entitlements.effectiveTier(now),
            monitoringEnabled = RoomMonitorStore.isMonitoringEnabled(app),
            intervalMinutes = config.cycleIntervalMinutes,
            liveTestDurationMs = config.liveTestPhaseDurationMs,
            monitoringNetworks = config.monitoringNetworks,
            manualNetwork = if (!wifiAvailable && resolvedManualNetwork == NetworkPreference.WIFI) {
                NetworkPreference.CELLULAR
            } else {
                resolvedManualNetwork
            },
            wifiAvailable = wifiAvailable,
            lightDownBytes = config.lightDownBytes,
            lightUpBytes = config.lightUpBytes,
            sustainedProbeEnabled = config.sustainedProbeEnabled,
            recentPings = store.recentPings(120).reversed(), // oldest-first for the chart
            recentThroughput = store.throughputBetween(dayAgo, now).takeLast(8),
            latestDownloadSweep = latestSweeps.download,
            latestUploadSweep = latestSweeps.upload,
            latencyMedianMs = Stats.median(rtts),
            jitterMs = if (rtts.size >= 2) Stats.stdDev(rtts) else null,
            lossPct = lossPct,
            dropCount = drops.size,
            stability = if (pings.isEmpty()) null else StabilityScore.compute(
                windowStartEpochMs = dayAgo,
                nowEpochMs = now,
                drops = drops,
                avgJitterMs = if (rtts.size >= 2) Stats.stdDev(rtts) else null,
                avgLossPct = lossPct ?: 0.0,
            ),
            pendingReport = RoomMonitorStore.pendingReport(app),
            meteredBytesThisMonth = store.bytesUsedSince(monthStart, metered = true),
            projectedMeteredBytesPerMonth = DataBudget.project(config).meteredBytesPerMonth,
        )
    }

    /**
     * Run one cycle right now, on demand.
     *
     * Free at every tier, deliberately — this is the whole app for someone who
     * just wants to check their connection, and it costs nothing per run since
     * the measurement is client-side against public endpoints. What Pro buys is
     * having this happen *without being asked*, which is the part that takes
     * ongoing work to keep reliable.
     */
    fun runManualTest() {
        if (_state.value.manualTestRunning) return
        val runGeneration = ++manualRunGeneration
        manualTestJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    manualTestRunning = true,
                    ranSustainedProbe = false,
                    manualTestError = null,
                    manualTestStep = 0,
                    manualTestStepLabel = "Connecting to ${if (it.manualNetwork == NetworkPreference.CELLULAR) "mobile data" else "Wi-Fi"}",
                    manualTestPhaseDurationMs = null,
                    manualTestElapsedMs = 0L,
                    livePings = emptyList(),
                    liveDownloadMbps = emptyList(),
                    liveUploadMbps = emptyList(),
                    latestDownloadSweep = emptyList(),
                    latestUploadSweep = emptyList(),
                    liveLog = emptyList(),
                )
            }

            val app = getApplication<Application>()
            // Raises the process's importance and gives the run a lock-screen/
            // notification-shade surface, so a minimized app does not silently
            // lose or hide a multi-minute mobile test. See
            // LiveTestForegroundService's doc comment.
            LiveTestForegroundService.start(app, phaseProgressLabel(_state.value))
            val config = store.loadConfig()
            val deviceState = readDeviceState()
            // Throttled independently per direction so a 200ms-cadence Rate
            // stream does not flood the log with a line per sample; once a
            // second is enough to show the number is moving.
            var lastDownRateLogAt = 0L
            var lastUpRateLogAt = 0L
            try {
                LiveTestRunner(
                    context = app,
                    store = store,
                    probeConfig = config,
                    fallbackNetworkType = deviceState.networkType,
                    wifiSsid = deviceState.ssid,
                ).runSession { sample ->
                    // A cancelled/older run must never repaint a later run or
                    // replace its selected mobile result with a stale Wi-Fi one.
                    if (runGeneration != manualRunGeneration) return@runSession

                    // The step comes only from an explicit Phase sample. Deriving
                    // it from whichever measurement arrived last let the ping
                    // loop — which runs underneath the stream phases — keep
                    // resetting the visible step to "Ping".
                    if (sample is LiveSample.Phase) startPhaseTimer(runGeneration, sample)

                    // One log line per event, covering every phase — not just
                    // ping. This is what used to make a running stream or a
                    // multi-minute cellular sweep indistinguishable from a
                    // hang: the probe log only ever rendered ping samples, so
                    // nothing visibly happened once the ping phase ended.
                    val logLine: String? = when (sample) {
                        is LiveSample.Phase -> "▶ ${sample.phase.label}"
                        is LiveSample.Ping -> "Ping " + (sample.rttMs?.let { "${it.toInt()} ms" } ?: "no reply")
                        is LiveSample.Rate -> {
                            val last = if (sample.direction == SweepRunner.Direction.DOWN) lastDownRateLogAt else lastUpRateLogAt
                            if (sample.atEpochMs - last >= 1_000L) {
                                if (sample.direction == SweepRunner.Direction.DOWN) {
                                    lastDownRateLogAt = sample.atEpochMs
                                } else {
                                    lastUpRateLogAt = sample.atEpochMs
                                }
                                val dir = if (sample.direction == SweepRunner.Direction.DOWN) "Down" else "Up"
                                "$dir ${formatMbps(sample.mbps)}"
                            } else {
                                null
                            }
                        }
                        is LiveSample.Sweep -> {
                            val dir = if (sample.direction == SweepRunner.Direction.DOWN) "Download" else "Upload"
                            val passed = sample.results.count { it.outcome == SweepResult.Outcome.ALL_PASSED }
                            "$dir sweep finished · $passed/${sample.results.size} sizes fully passed"
                        }
                        is LiveSample.SweepStepResult -> {
                            val dir = if (sample.direction == SweepRunner.Direction.DOWN) "Down" else "Up"
                            val verdict = when (sample.result.outcome) {
                                SweepResult.Outcome.ALL_PASSED -> "passed"
                                SweepResult.Outcome.ALL_FAILED -> "failed"
                                SweepResult.Outcome.MIXED -> "${sample.result.passCount}/${sample.result.attempted} passed"
                                SweepResult.Outcome.INCOMPLETE -> "incomplete"
                            }
                            val rate = sample.result.observedMbps?.let { " · ${formatMbps(it)}" } ?: ""
                            "$dir sweep ${sample.stepIndex}/${sample.stepCount} · ${formatBytesShort(sample.result.bytes)} $verdict$rate"
                        }
                    }

                    _state.update { current ->
                        val withLog = if (logLine != null) {
                            current.copy(liveLog = (current.liveLog + LiveLogEntry(sample.atEpochMs, logLine)).takeLast(200))
                        } else {
                            current
                        }
                        when (sample) {
                            is LiveSample.Phase -> {
                                val enteringSustained = sample.phase == LivePhase.SUSTAINED_DOWNLOAD ||
                                    sample.phase == LivePhase.SUSTAINED_UPLOAD
                                withLog.copy(
                                    manualTestStep = sample.step,
                                    manualTestTotalSteps = sample.totalSteps,
                                    manualTestStepLabel = sample.phase.label,
                                    manualTestPhaseDurationMs = sample.durationMs,
                                    // The sustained probe's decay curve needs a
                                    // clean chart: mixing in the short regular
                                    // stream's samples would corrupt both the
                                    // visual and the peak/steady-state summary
                                    // below it.
                                    liveDownloadMbps = if (sample.phase == LivePhase.SUSTAINED_DOWNLOAD) emptyList() else withLog.liveDownloadMbps,
                                    liveUploadMbps = if (sample.phase == LivePhase.SUSTAINED_UPLOAD) emptyList() else withLog.liveUploadMbps,
                                    ranSustainedProbe = withLog.ranSustainedProbe || enteringSustained,
                                )
                            }
                            is LiveSample.Rate -> when (sample.direction) {
                                SweepRunner.Direction.DOWN -> withLog.copy(
                                    liveDownloadMbps = (withLog.liveDownloadMbps + sample.mbps).takeLast(120),
                                )
                                SweepRunner.Direction.UP -> withLog.copy(
                                    liveUploadMbps = (withLog.liveUploadMbps + sample.mbps).takeLast(120),
                                )
                            }
                            is LiveSample.Ping -> withLog.copy(
                                livePings = (withLog.livePings + PingSample(
                                    atEpochMs = sample.atEpochMs,
                                    rttMs = sample.rttMs,
                                    networkType = deviceState.networkType,
                                )).takeLast(120),
                            )
                            is LiveSample.Sweep -> when (sample.direction) {
                                SweepRunner.Direction.DOWN -> withLog.copy(latestDownloadSweep = sample.results)
                                SweepRunner.Direction.UP -> withLog.copy(latestUploadSweep = sample.results)
                            }
                            is LiveSample.SweepStepResult -> withLog
                        }
                    }

                    if (sample is LiveSample.Phase) LiveTestForegroundService.update(phaseProgressLabel(_state.value))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (runGeneration == manualRunGeneration) {
                    val selected = when (config.preferredTestNetwork) {
                        NetworkPreference.CELLULAR -> "Mobile data"
                        NetworkPreference.WIFI -> "Wi-Fi"
                        NetworkPreference.AUTO -> "The selected network"
                    }
                    _state.update {
                        it.copy(manualTestError = if (t is NetworkBinder.RequestedNetworkUnavailable) {
                            "$selected is unavailable. The test did not fall back to another connection."
                        } else "Test could not start on $selected.")
                    }
                }
            } finally {
                if (runGeneration == manualRunGeneration) {
                    manualTimerJob?.cancel()
                    manualTimerJob = null
                    LiveTestForegroundService.stop(app)
                    _state.update {
                        it.copy(
                            manualTestRunning = false,
                            manualTestElapsedMs = 0L,
                            manualTestPhaseDurationMs = null,
                        )
                    }
                    refresh()
                    manualTestJob = null
                }
            }
        }
    }

    fun stopManualTest() {
        manualRunGeneration++
        manualTestJob?.cancel()
        manualTestJob = null
        manualTimerJob?.cancel()
        manualTimerJob = null
        LiveTestForegroundService.stop(getApplication())
        _state.update {
            it.copy(manualTestRunning = false, manualTestElapsedMs = 0L, manualTestPhaseDurationMs = null)
        }
    }

    /**
     * Restarts the countdown at each phase boundary.
     *
     * The configured duration applies per phase, so a single whole-run
     * stopwatch would show a 10-second test counting past 30 seconds. Each
     * phase counts its own clock instead.
     */
    private fun startPhaseTimer(runGeneration: Long, phase: LiveSample.Phase) {
        manualTimerJob?.cancel()
        // elapsedRealtime, not wall-clock time: an NTP correction or a manual
        // clock change mid-test would otherwise make the countdown jump — or
        // run backwards — for a duration that did not actually change. Wall
        // clock stays for persisted timestamps, where it is the right answer.
        val startedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(manualTestElapsedMs = 0L) }
        manualTimerJob = viewModelScope.launch {
            while (runGeneration == manualRunGeneration) {
                _state.update {
                    it.copy(manualTestElapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
                }
                LiveTestForegroundService.update(phaseProgressLabel(_state.value))
                delay(1_000)
            }
        }
    }

    fun setLiveTestDuration(durationMs: Long) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(liveTestPhaseDurationMs = durationMs))
        refresh()
    }

    fun setMonitoring(enabled: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()

        // Checked here as well as in the worker. Hiding a switch is
        // presentation; this is the gate next to the effect.
        if (enabled && !FeatureGate.shouldBackgroundWorkRun(entitlements.current(), now)) {
            refresh()
            return@launch
        }

        RoomMonitorStore.setMonitoringEnabled(app, enabled)
        if (enabled) {
            // Baseline for the first scheduled report, so the first worker wake
            // does not treat "never delivered" as "overdue since 1970".
            RoomMonitorStore.markReportingStarted(app, now)
            ProbeWorker.scheduleFromConfig(app, store)
            ReportWorker.schedule(app)
            MonitoringNotification.active(app)
        } else {
            ProbeWorker.cancel(app)
            ReportWorker.cancel(app)
            MonitoringNotification.cancel(app)
        }
        refresh()
    }

    fun acknowledgeReport() = viewModelScope.launch {
        RoomMonitorStore.clearPendingReport(getApplication())
        refresh()
    }

    fun setInterval(minutes: Int) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (minutes !in FeatureGate.allowedIntervalMinutes(entitlements.effectiveTier())) return@launch
        store.saveConfig(store.loadConfig().copy(
            cycleIntervalMinutes = minutes,
            // A daily or weekly recurrence is normally chosen specifically to
            // run one bounded speed test at that cadence, not to wait through
            // four calendar cycles before the first one.
            throughputEveryNCycles = if (minutes >= 24 * 60) 1 else store.loadConfig().throughputEveryNCycles,
        ))
        if (RoomMonitorStore.isMonitoringEnabled(app)) ProbeWorker.scheduleFromConfig(app, store)
        refresh()
    }

    fun setMonitoringNetworks(networks: Set<NetworkType>) = viewModelScope.launch {
        val selected = networks.intersect(setOf(NetworkType.WIFI, NetworkType.CELLULAR))
        if (selected.isEmpty()) return@launch
        val preference = when (selected) {
            setOf(NetworkType.WIFI) -> NetworkPreference.WIFI
            setOf(NetworkType.CELLULAR) -> NetworkPreference.CELLULAR
            else -> NetworkPreference.AUTO
        }
        store.saveConfig(store.loadConfig().copy(
            monitoringNetworks = selected,
            preferredTestNetwork = preference,
        ))
        refresh()
    }

    fun setManualNetwork(network: NetworkPreference) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(preferredTestNetwork = network))
        refresh()
    }

    /**
     * Same persisted field, same effect, as [de.sevenapp.monitor.android.ui.SettingsViewModel.setSustainedProbe] —
     * mirrored here so the switch on this screen and the one in Settings
     * agree without one of them needing to own the setting.
     */
    fun setSustainedProbe(enabled: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(sustainedProbeEnabled = enabled))
        refresh()
    }

    fun setMeasurementSize(bytes: Int) = viewModelScope.launch {
        // Mobile's light test intentionally uses the same size in both
        // directions. That makes its battery/data cost easy to understand.
        store.saveConfig(store.loadConfig().copy(lightDownBytes = bytes, lightUpBytes = bytes))
        refresh()
    }

    private fun readDeviceState(): DeviceState {
        val context = getApplication<Application>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val type = when {
            caps == null -> NetworkType.NONE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true

        val ssid = if (type == NetworkType.WIFI) {
            context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
                ?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }
        } else null
        return DeviceState(networkType = type, isCharging = charging, isMetered = metered, ssid = ssid)
    }
}

/** "16KB", "1MB" — compact enough for one probe-log line. */
internal fun formatBytesShort(bytes: Int): String = when {
    bytes % 1_000_000 == 0 -> "${bytes / 1_000_000}MB"
    bytes % 1_000 == 0 -> "${bytes / 1_000}KB"
    else -> "${bytes}B"
}
