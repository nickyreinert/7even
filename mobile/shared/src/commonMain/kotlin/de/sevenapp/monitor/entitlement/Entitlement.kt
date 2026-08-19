package de.sevenapp.monitor.entitlement

/**
 * What the user has paid for.
 *
 * The product split: **one-off measurement is free, unattended repeated
 * measurement is paid.** That is the honest line to draw, because repeated
 * background monitoring is what actually costs something to operate and what
 * takes ongoing work to keep reliable across OS versions and OEM battery
 * quirks. A single manual test costs us nothing per run — the measurement is
 * client-side against public endpoints — so charging for it would be charging
 * for nothing.
 */
enum class Tier { FREE, PRO }

/**
 * @param expiresAtEpochMs null for FREE, or a perpetual/unknown-expiry PRO
 *        grant. A subscription sets this.
 * @param isTrial a PRO grant the user has not paid for yet.
 */
data class Entitlement(
    val tier: Tier,
    val expiresAtEpochMs: Long? = null,
    val isTrial: Boolean = false,
) {
    companion object {
        val FREE = Entitlement(Tier.FREE)

        /**
         * Days after expiry during which PRO features keep working.
         *
         * A card that fails to renew is usually a payment glitch, not a
         * decision to stop paying. Cutting a monitoring app off the instant a
         * renewal hiccups puts a *gap in the user's data* — the one thing they
         * cannot go back and recreate. The grace window is for their data's
         * sake, not as a sales tactic.
         */
        const val GRACE_DAYS = 3
        const val GRACE_MS = GRACE_DAYS * 24L * 60 * 60 * 1000
    }

    /** Paid access, ignoring grace. */
    fun isActiveAt(nowEpochMs: Long): Boolean {
        if (tier == Tier.FREE) return false
        val expiry = expiresAtEpochMs ?: return true
        return nowEpochMs < expiry
    }

    /** Paid access including the grace window after expiry. */
    fun isActiveOrInGraceAt(nowEpochMs: Long): Boolean {
        if (tier == Tier.FREE) return false
        val expiry = expiresAtEpochMs ?: return true
        return nowEpochMs < expiry + GRACE_MS
    }

    fun isInGraceAt(nowEpochMs: Long): Boolean =
        !isActiveAt(nowEpochMs) && isActiveOrInGraceAt(nowEpochMs)

    fun effectiveTierAt(nowEpochMs: Long): Tier =
        if (isActiveOrInGraceAt(nowEpochMs)) Tier.PRO else Tier.FREE
}

/**
 * Whether the paywall is switched on at all.
 *
 * **Currently off, deliberately.** Getting onto Play with a new personal
 * developer account requires a closed test with 12 opted-in testers for 14
 * continuous days — and the feature those testers most need to exercise is
 * background monitoring, which is precisely what the paywall would lock. A
 * paywalled closed test would mean testing everything except the thing that
 * actually has to work.
 *
 * So: ship free, get through closed testing, switch this on when Play Billing
 * is integrated. The gating logic below is written and tested for both states,
 * so turning it on is a flag change rather than a feature build.
 */
object PaywallConfig {
    const val ENABLED: Boolean = false
}

/**
 * The single place that decides what a tier may do.
 *
 * Deliberately a pure function of tier and time, in commonMain, so the same
 * answers hold on Android and iOS and so the rules can be unit-tested. Both the
 * UI *and* the scheduler consult this: hiding a button is presentation, not
 * enforcement, and an entitlement that lapses while background work is already
 * scheduled has to actually stop that work.
 */
object FeatureGate {

    /**
     * The one place a tier is resolved. Everything — UI, workers, retention —
     * must go through this rather than reading [Entitlement.effectiveTierAt]
     * directly, so that [PaywallConfig] genuinely is a single switch.
     *
     * With the paywall off, everyone is PRO. Not "FREE with checks skipped":
     * making the tier itself PRO means every downstream rule (retention,
     * intervals, reports) behaves exactly as it will once billing ships, so
     * closed testing exercises the real thing.
     */
    fun resolveTier(
        entitlement: Entitlement,
        nowEpochMs: Long,
        paywallEnabled: Boolean = PaywallConfig.ENABLED,
    ): Tier = if (!paywallEnabled) Tier.PRO else entitlement.effectiveTierAt(nowEpochMs)

    /**
     * On-demand testing is free, deliberately and permanently.
     *
     * This is the whole app for someone who just wants to check their
     * connection right now, and it is what makes the free tier genuinely
     * useful rather than a crippled demo.
     */
    fun canRunManualTest(tier: Tier): Boolean = true

    /** The paid line: unattended, repeated measurement. */
    fun canScheduleBackgroundMonitoring(tier: Tier): Boolean = tier == Tier.PRO

    /** Periodic reports are built from background data, so they follow it. */
    fun canReceiveScheduledReports(tier: Tier): Boolean = tier == Tier.PRO

    /**
     * Export is free at every tier.
     *
     * The data is a record of the user's own connection, collected on their own
     * device. Holding it hostage behind a subscription would be indefensible
     * regardless of what the law in a given market requires, and it is the kind
     * of thing that earns a one-star review that is entirely deserved.
     */
    fun canExport(tier: Tier): Boolean = true

    /**
     * How long samples are kept.
     *
     * Free keeps enough to make the live view and a day's history useful; PRO
     * keeps enough to see a trend, which is the point of monitoring over time.
     */
    fun retentionDays(tier: Tier): Int = when (tier) {
        Tier.FREE -> 2
        Tier.PRO -> 90
    }

    /**
     * Intervals a tier may select. FREE gets none, because FREE cannot schedule
     * at all — the list is empty rather than "15 minutes but disabled", so the
     * UI cannot accidentally offer a value the scheduler will refuse.
     */
    fun allowedIntervalMinutes(tier: Tier): List<Int> = when (tier) {
        Tier.FREE -> emptyList()
        Tier.PRO -> listOf(15, 30, 60, 120, 360)
    }

    /**
     * What should happen to already-collected data when PRO lapses.
     *
     * Never delete it. Collection stops; the record stays readable and
     * exportable. Deleting a user's history because they stopped paying would
     * be destroying something they cannot recreate — the measurements were of a
     * moment that has passed.
     */
    fun retainDataOnLapse(): Boolean = true

    /**
     * Retention actually applied by the pruner.
     *
     * This is not simply `retentionDays(currentTier)`, and the difference
     * matters: a lapsed Pro user dropping to a 2-day window would have 88 days
     * of history deleted by the next prune, which is precisely what
     * [retainDataOnLapse] promises never happens. The window therefore
     * ratchets — once Pro has been held, the longer retention sticks.
     *
     * The cost is some disk for a user who never returns. That is a much
     * smaller problem than silently destroying the record they were paying to
     * build.
     */
    fun effectiveRetentionDays(currentTier: Tier, hasEverHadPro: Boolean): Int =
        if (hasEverHadPro) retentionDays(Tier.PRO) else retentionDays(currentTier)

    /**
     * Whether the scheduler should be running right now. The worker checks this
     * on every wakeup, so an entitlement that lapses between cycles stops
     * collection without needing the app to be opened.
     */
    fun shouldBackgroundWorkRun(
        entitlement: Entitlement,
        nowEpochMs: Long,
        paywallEnabled: Boolean = PaywallConfig.ENABLED,
    ): Boolean = canScheduleBackgroundMonitoring(resolveTier(entitlement, nowEpochMs, paywallEnabled))
}

/**
 * A locked feature and why, so the UI can explain rather than just grey
 * something out. A disabled control with no reason is the most annoying
 * possible paywall.
 */
data class LockedFeature(val name: String, val reason: String)

object Paywall {

    /**
     * Whether the plan/upgrade UI should appear at all. With the paywall off
     * there is nothing to sell, and showing an "Upgrade" button that cannot
     * take money is worse than showing nothing.
     */
    fun shouldShowPlanUi(paywallEnabled: Boolean = PaywallConfig.ENABLED): Boolean = paywallEnabled

    fun lockedFor(tier: Tier): List<LockedFeature> = when (tier) {
        Tier.PRO -> emptyList()
        Tier.FREE -> listOf(
            LockedFeature(
                "Background monitoring",
                "Measures on a schedule while the app is closed, so drops are caught when they happen rather than when you next look.",
            ),
            LockedFeature(
                "Daily / weekly / monthly reports",
                "Summaries of uptime, latency and drops over time, delivered as a notification.",
            ),
            LockedFeature(
                "90-day history",
                "Free keeps ${FeatureGate.retentionDays(Tier.FREE)} days; Pro keeps ${FeatureGate.retentionDays(Tier.PRO)}.",
            ),
        )
    }

    /** Stated plainly so the free tier never feels like a bait-and-switch. */
    fun freeAlwaysIncludes(): List<String> = listOf(
        "Run a connection test whenever you want",
        "Live latency, jitter, loss and speed while the app is open",
        "Export your data",
    )
}
