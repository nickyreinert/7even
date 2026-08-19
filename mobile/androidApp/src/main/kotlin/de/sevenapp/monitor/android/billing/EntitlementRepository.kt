package de.sevenapp.monitor.android.billing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.sevenapp.monitor.entitlement.Entitlement
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.entitlementStore by preferencesDataStore("entitlement")

/**
 * Where the app's current entitlement is cached.
 *
 * ⚠️ **There is no real billing here yet.** This stores whatever it is told and
 * has no connection to Google Play. Selling a subscription on Play requires the
 * Play Billing Library and server-side verification of the purchase token —
 * Play policy requires Play Billing for digital goods, and a client-side-only
 * entitlement is trivially defeated by anyone who wants to.
 *
 * The reason it exists in this shape now is that [Entitlement] and
 * `FeatureGate` are the parts worth designing carefully — what is free, what
 * lapsing does to the user's data, how grace works. Those are decided and
 * tested. Swapping this cache's *source* from "whatever was set locally" to
 * "verified Play purchase" is a contained change; the gating logic above it
 * does not move.
 *
 * Until that happens, treat [grantProForTesting] as exactly what its name says.
 */
class EntitlementRepository(private val context: Context) {

    fun observe(): Flow<Entitlement> = context.entitlementStore.data.map { it.toEntitlement() }

    suspend fun current(): Entitlement = context.entitlementStore.data.first().toEntitlement()

    /**
     * Local-only grant, for development and for the eventual "restore purchase"
     * path to write into once a verified purchase exists. Not a purchase.
     */
    suspend fun grantProForTesting(expiresAtEpochMs: Long?, isTrial: Boolean = false) {
        context.entitlementStore.edit {
            it[KEY_TIER] = Tier.PRO.name
            if (expiresAtEpochMs != null) it[KEY_EXPIRES] = expiresAtEpochMs else it.remove(KEY_EXPIRES)
            it[KEY_TRIAL] = isTrial
            // Sticky, and never cleared by clear(). It drives the retention
            // ratchet, so unsetting it would let a lapse prune away history the
            // user built while paying.
            it[KEY_EVER_PRO] = true
        }
    }

    /**
     * Whether Pro was ever held. Feeds `FeatureGate.effectiveRetentionDays`, so
     * a lapse stops collection without shrinking the window over data already
     * collected.
     */
    suspend fun hasEverHadPro(): Boolean = context.entitlementStore.data.first()[KEY_EVER_PRO] ?: false

    /**
     * The one place the app asks "what tier is this user, right now".
     *
     * Also records the Pro high-water mark as a side effect, and that side
     * effect is load-bearing: while [PaywallConfig] is off everyone resolves to
     * PRO, so everyone gets the 90-day retention window. If the paywall is
     * later switched on, those users resolve to FREE — and without this flag
     * the next prune would delete up to 88 days of history they had already
     * collected. Writing it here means the existing retention ratchet protects
     * them automatically, with no migration step to remember at launch.
     */
    suspend fun effectiveTier(nowEpochMs: Long = System.currentTimeMillis()): Tier {
        val tier = FeatureGate.resolveTier(current(), nowEpochMs)
        if (tier == Tier.PRO && !hasEverHadPro()) {
            context.entitlementStore.edit { it[KEY_EVER_PRO] = true }
        }
        return tier
    }

    /** Retention window to prune at, already ratcheted. */
    suspend fun retentionDays(nowEpochMs: Long = System.currentTimeMillis()): Int =
        FeatureGate.effectiveRetentionDays(effectiveTier(nowEpochMs), hasEverHadPro())

    /** Drops to Free. Deliberately leaves KEY_EVER_PRO set — see the ratchet above. */
    suspend fun clear() {
        context.entitlementStore.edit {
            it[KEY_TIER] = Tier.FREE.name
            it.remove(KEY_EXPIRES)
            it[KEY_TRIAL] = false
        }
    }

    private fun androidx.datastore.preferences.core.Preferences.toEntitlement(): Entitlement {
        // Unknown/absent tier degrades to FREE. Failing *closed* is right for a
        // paid gate — a corrupt preference must not hand out Pro.
        val tier = this[KEY_TIER]
            ?.let { name -> runCatching { Tier.valueOf(name) }.getOrNull() }
            ?: Tier.FREE
        return Entitlement(
            tier = tier,
            expiresAtEpochMs = this[KEY_EXPIRES],
            isTrial = this[KEY_TRIAL] ?: false,
        )
    }

    companion object {
        private val KEY_TIER = stringPreferencesKey("tier")
        private val KEY_EXPIRES = longPreferencesKey("expires_at")
        private val KEY_TRIAL = booleanPreferencesKey("is_trial")
        private val KEY_EVER_PRO = booleanPreferencesKey("has_ever_had_pro")

        @Volatile private var instance: EntitlementRepository? = null

        fun get(context: Context): EntitlementRepository = instance ?: synchronized(this) {
            instance ?: EntitlementRepository(context.applicationContext).also { instance = it }
        }
    }
}
