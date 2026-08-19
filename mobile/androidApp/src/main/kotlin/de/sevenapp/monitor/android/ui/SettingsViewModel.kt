package de.sevenapp.monitor.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sevenapp.monitor.android.billing.EntitlementRepository
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.android.work.ProbeWorker
import de.sevenapp.monitor.android.work.ReportWorker
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.probe.DataBudget
import de.sevenapp.monitor.probe.SweepPlan
import de.sevenapp.monitor.probe.SweepStep
import de.sevenapp.monitor.report.ReportPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val tier: Tier = Tier.FREE,
    val inGrace: Boolean = false,
    val expiryLabel: String? = null,
    val monitoringEnabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val automaticHourOfDay: Int = 0,
    val automaticDayOfWeek: Int = 1,
    val monitoringNetworks: Set<NetworkType> = setOf(NetworkType.WIFI, NetworkType.CELLULAR),
    val automaticStreamEnabled: Boolean = false,
    val automaticSweepEnabled: Boolean = false,
    val automaticRequiresCharging: Boolean = false,
    val manualStreamDurationMs: Long = 60_000L,
    val traceUrl: String = "",
    val downUrlTemplate: String = "",
    val upUrl: String = "",
    val streamUrl: String = "",
    val useWebSocketStream: Boolean = false,
    val liveTestSweepEnabled: Boolean = true,
    val wifiSweepSteps: List<SweepStep> = SweepPlan.DEFAULT,
    val mobileSweepSteps: List<SweepStep> = SweepPlan.MOBILE_DEFAULT,
    val wifiMeasurementSizes: Set<Int> = emptySet(),
    val cellularMeasurementSizes: Set<Int> = emptySet(),
    val reportPeriod: ReportPeriod = ReportPeriod.WEEKLY,
    val projectedMeteredBytesPerMonth: Long = 0,
    val meteredBytesThisMonth: Long = 0,
    val billingUnavailableMessage: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RoomMonitorStore.get(app)
    private val entitlements = EntitlementRepository.get(app)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val entitlement = entitlements.current()
        val config = store.loadConfig()
        val monthStart = now - 30L * 24 * 60 * 60 * 1000

        _state.value = SettingsState(
            tier = entitlements.effectiveTier(now),
            inGrace = entitlement.isInGraceAt(now),
            expiryLabel = entitlement.expiresAtEpochMs?.let { "Renews or expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}" },
            monitoringEnabled = RoomMonitorStore.isMonitoringEnabled(app),
            intervalMinutes = config.cycleIntervalMinutes,
            automaticHourOfDay = config.automaticHourOfDay,
            automaticDayOfWeek = config.automaticDayOfWeek,
            monitoringNetworks = config.monitoringNetworks,
            automaticStreamEnabled = config.automaticStreamEnabled,
            automaticSweepEnabled = config.automaticSweepEnabled,
            automaticRequiresCharging = config.automaticRequiresCharging,
            manualStreamDurationMs = config.liveTestMinDurationMs,
            traceUrl = config.traceUrl,
            downUrlTemplate = config.downUrlTemplate,
            upUrl = config.upUrl,
            streamUrl = config.streamUrl,
            useWebSocketStream = config.useWebSocketStream,
            liveTestSweepEnabled = config.liveTestSweepEnabled,
            wifiSweepSteps = config.wifiLiveTestSweepSteps,
            mobileSweepSteps = config.mobileLiveTestSweepSteps,
            wifiMeasurementSizes = config.wifiMeasurementSizes,
            cellularMeasurementSizes = config.cellularMeasurementSizes,
            reportPeriod = RoomMonitorStore.reportPeriod(app),
            projectedMeteredBytesPerMonth = DataBudget.project(config).meteredBytesPerMonth,
            meteredBytesThisMonth = store.bytesUsedSince(monthStart, metered = true),
        )
    }

    fun setMonitoring(enabled: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()

        // Re-check rather than trusting that the UI only showed this control to
        // eligible users. The gate belongs next to the effect.
        if (enabled && !FeatureGate.shouldBackgroundWorkRun(entitlements.current(), now)) {
            refresh()
            return@launch
        }

        RoomMonitorStore.setMonitoringEnabled(app, enabled)
        if (enabled) {
            store.loadConfig().let { ProbeWorker.schedule(app, it.cycleIntervalMinutes.toLong(), it.automaticHourOfDay, it.automaticDayOfWeek) }
            ReportWorker.schedule(app)
        } else {
            ProbeWorker.cancel(app)
            ReportWorker.cancel(app)
        }
        refresh()
    }

    fun setInterval(minutes: Int) = viewModelScope.launch {
        val app = getApplication<Application>()
        val tier = entitlements.effectiveTier()
        if (minutes !in FeatureGate.allowedIntervalMinutes(tier)) return@launch

        val config = store.loadConfig().copy(
            cycleIntervalMinutes = minutes,
            throughputEveryNCycles = if (minutes >= 24 * 60) 1 else store.loadConfig().throughputEveryNCycles,
        )
        store.saveConfig(config)
        if (RoomMonitorStore.isMonitoringEnabled(app)) ProbeWorker.schedule(app, minutes.toLong(), config.automaticHourOfDay, config.automaticDayOfWeek)
        refresh()
    }

    fun setAutomaticScheduleTime(hourOfDay: Int, dayOfWeek: Int = _state.value.automaticDayOfWeek) = viewModelScope.launch {
        val app = getApplication<Application>()
        val config = store.loadConfig().copy(
            automaticHourOfDay = hourOfDay.coerceIn(0, 23),
            automaticDayOfWeek = dayOfWeek.coerceIn(1, 7),
        )
        store.saveConfig(config)
        if (RoomMonitorStore.isMonitoringEnabled(app)) {
            ProbeWorker.schedule(app, config.cycleIntervalMinutes.toLong(), config.automaticHourOfDay, config.automaticDayOfWeek)
        }
        refresh()
    }

    fun setMonitoringNetworks(networks: Set<NetworkType>) = viewModelScope.launch {
        // The UI always supplies one of Wi-Fi, mobile, or both. Keeping this
        // guard at the data boundary also prevents a saved empty set from
        // silently disabling monitoring.
        val allowed = networks.intersect(setOf(NetworkType.WIFI, NetworkType.CELLULAR))
        if (allowed.isEmpty()) return@launch
        store.saveConfig(store.loadConfig().copy(
            monitoringNetworks = allowed,
            preferredTestNetwork = allowed.toPreference(),
        ))
        refresh()
    }

    fun toggleMonitoringNetwork(network: NetworkType, enabled: Boolean) = viewModelScope.launch {
        val config = store.loadConfig()
        val networks = config.monitoringNetworks.toMutableSet()
        if (enabled) networks += network else networks -= network
        if (networks.isEmpty()) return@launch
        store.saveConfig(config.copy(
            monitoringNetworks = networks,
            preferredTestNetwork = networks.toPreference(),
        ))
        refresh()
    }

    fun toggleMeasurementSize(network: NetworkType, bytes: Int, enabled: Boolean) = viewModelScope.launch {
        val config = store.loadConfig()
        val sizes = when (network) {
            NetworkType.WIFI -> config.wifiMeasurementSizes.toMutableSet()
            NetworkType.CELLULAR -> config.cellularMeasurementSizes.toMutableSet()
            else -> return@launch
        }
        if (enabled) sizes += bytes else sizes -= bytes
        if (sizes.isEmpty()) return@launch
        store.saveConfig(
            if (network == NetworkType.WIFI) config.copy(wifiMeasurementSizes = sizes)
            else config.copy(cellularMeasurementSizes = sizes),
        )
        refresh()
    }

    fun setAutomaticStream(enabled: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(automaticStreamEnabled = enabled))
        refresh()
    }

    fun setAutomaticSweep(enabled: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(automaticSweepEnabled = enabled))
        refresh()
    }

    fun setAutomaticRequiresCharging(enabled: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(automaticRequiresCharging = enabled))
        refresh()
    }

    fun setManualStreamDuration(durationMs: Long) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(liveTestMinDurationMs = durationMs))
        refresh()
    }

    fun clearHistory() = viewModelScope.launch {
        store.clearHistory()
        refresh()
    }

    fun setLiveSweep(enabled: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(liveTestSweepEnabled = enabled))
        refresh()
    }

    fun setSweepSteps(network: NetworkType, steps: List<SweepStep>) = viewModelScope.launch {
        val valid = steps.filter { it.bytes in 1..SweepPlan.MAX_BYTES && it.trials in 1..SweepPlan.MAX_TRIALS }
            .ifEmpty { SweepPlan.DEFAULT }
        val config = store.loadConfig()
        store.saveConfig(if (network == NetworkType.CELLULAR) config.copy(mobileLiveTestSweepSteps = valid)
        else config.copy(wifiLiveTestSweepSteps = valid))
        refresh()
    }

    fun setEndpoints(
        traceUrl: String,
        downUrlTemplate: String,
        upUrl: String,
        streamUrl: String,
        useWebSocketStream: Boolean,
    ) = viewModelScope.launch {
        // A malformed URL must not be allowed to break every background cycle.
        // The download URL retains {bytes}, which is replaced with the selected
        // packet size immediately before a test runs.
        val validHttp = { url: String -> url.startsWith("https://") }
        val validWs = { url: String -> url.startsWith("wss://") }
        if (!validHttp(traceUrl) || !validHttp(downUrlTemplate) || !validHttp(upUrl) ||
            !downUrlTemplate.contains("{bytes}") || (useWebSocketStream && !validWs(streamUrl))) {
            return@launch
        }
        store.saveConfig(store.loadConfig().copy(
            traceUrl = traceUrl.trim(),
            downUrlTemplate = downUrlTemplate.trim(),
            upUrl = upUrl.trim(),
            streamUrl = streamUrl.trim(),
            useWebSocketStream = useWebSocketStream,
        ))
        refresh()
    }

    fun setReportPeriod(period: ReportPeriod) = viewModelScope.launch {
        RoomMonitorStore.setReportPeriod(getApplication(), period)
        refresh()
    }

    /**
     * ⚠️ No purchase flow exists yet. Play policy requires Google Play Billing
     * for digital goods, which needs the billing library plus server-side
     * verification of the purchase token — deliberately not faked here, because
     * a button that pretends to sell something is worse than one that says it
     * cannot yet.
     */
    fun startUpgrade() = viewModelScope.launch {
        _state.value = _state.value.copy(
            billingUnavailableMessage = "Purchasing is not wired up yet — Play Billing is still to be integrated.",
        )
    }

    fun manageSubscription() {
        val app = getApplication<Application>()
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** TODO: write the JSON to a user-chosen file via the storage access framework. */
    fun export() = viewModelScope.launch { /* not implemented */ }

    private fun Set<NetworkType>.toPreference(): NetworkPreference = when (this) {
        setOf(NetworkType.WIFI) -> NetworkPreference.WIFI
        setOf(NetworkType.CELLULAR) -> NetworkPreference.CELLULAR
        else -> NetworkPreference.AUTO
    }
}
