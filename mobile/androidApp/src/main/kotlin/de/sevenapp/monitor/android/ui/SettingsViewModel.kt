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
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.probe.DataBudget
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
    val throughputNetworks: Set<NetworkType> = emptySet(),
    val fullSweepRequiresCharging: Boolean = true,
    val liveTestMinDurationMs: Long = 60_000,
    val liveTestSweepEnabled: Boolean = true,
    val preferredTestNetwork: NetworkPreference = NetworkPreference.AUTO,
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
            throughputNetworks = config.throughputLightNetworks,
            fullSweepRequiresCharging = config.fullSweepRequiresCharging,
            liveTestMinDurationMs = config.liveTestMinDurationMs,
            liveTestSweepEnabled = config.liveTestSweepEnabled,
            preferredTestNetwork = config.preferredTestNetwork,
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
            ProbeWorker.schedule(app, store.loadConfig().cycleIntervalMinutes.toLong())
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

        store.saveConfig(store.loadConfig().copy(cycleIntervalMinutes = minutes))
        if (RoomMonitorStore.isMonitoringEnabled(app)) ProbeWorker.schedule(app, minutes.toLong())
        refresh()
    }

    fun setCellularThroughput(allowed: Boolean) = viewModelScope.launch {
        val config = store.loadConfig()
        val networks = config.throughputLightNetworks.toMutableSet().apply {
            if (allowed) add(NetworkType.CELLULAR) else remove(NetworkType.CELLULAR)
        }
        store.saveConfig(config.copy(throughputLightNetworks = networks))
        refresh()
    }

    fun setFullSweepRequiresCharging(required: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(fullSweepRequiresCharging = required))
        refresh()
    }

    fun setLiveTestDurationMinutes(minutes: Int) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(liveTestMinDurationMs = minutes * 60_000L))
        refresh()
    }

    fun setLiveTestSweepEnabled(enabled: Boolean) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(liveTestSweepEnabled = enabled))
        refresh()
    }

    fun setPreferredTestNetwork(preference: NetworkPreference) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(preferredTestNetwork = preference))
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
}
