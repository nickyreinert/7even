package de.sevenapp.monitor

import de.sevenapp.monitor.entitlement.Entitlement
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Paywall
import de.sevenapp.monitor.entitlement.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementTest {

    private val now = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun freeIsNeverActive() {
        assertFalse(Entitlement.FREE.isActiveAt(now))
        assertFalse(Entitlement.FREE.isActiveOrInGraceAt(now))
        assertEquals(Tier.FREE, Entitlement.FREE.effectiveTierAt(now))
    }

    @Test
    fun proWithoutExpiryIsAlwaysActive() {
        val e = Entitlement(Tier.PRO)
        assertTrue(e.isActiveAt(now))
        assertTrue(e.isActiveAt(now + 3650 * day))
    }

    @Test
    fun proBeforeExpiryIsActive() {
        val e = Entitlement(Tier.PRO, expiresAtEpochMs = now + day)
        assertTrue(e.isActiveAt(now))
    }

    @Test
    fun expiredProIsInGraceThenLapses() {
        val expiry = now
        val e = Entitlement(Tier.PRO, expiresAtEpochMs = expiry)

        // Just past expiry: not strictly active, but still served.
        assertFalse(e.isActiveAt(expiry + 1))
        assertTrue(e.isActiveOrInGraceAt(expiry + 1))
        assertTrue(e.isInGraceAt(expiry + 1))
        assertEquals(Tier.PRO, e.effectiveTierAt(expiry + 1))

        // Past the grace window: genuinely free again.
        val afterGrace = expiry + Entitlement.GRACE_MS + 1
        assertFalse(e.isActiveOrInGraceAt(afterGrace))
        assertFalse(e.isInGraceAt(afterGrace))
        assertEquals(Tier.FREE, e.effectiveTierAt(afterGrace))
    }

    @Test
    fun graceKeepsCollectionRunningThroughAFailedRenewal() {
        // The point of grace is that a payment glitch must not punch a hole in
        // the user's data — a gap they can never go back and recreate.
        val e = Entitlement(Tier.PRO, expiresAtEpochMs = now)
        assertTrue(FeatureGate.shouldBackgroundWorkRun(e, now + day))
        assertFalse(FeatureGate.shouldBackgroundWorkRun(e, now + (Entitlement.GRACE_DAYS + 1) * day))
    }
}

class FeatureGateTest {

    @Test
    fun manualTestingIsFreeAtEveryTier() {
        // The free tier has to be genuinely useful, not a crippled demo.
        assertTrue(FeatureGate.canRunManualTest(Tier.FREE))
        assertTrue(FeatureGate.canRunManualTest(Tier.PRO))
    }

    @Test
    fun backgroundMonitoringIsThePaidLine() {
        assertFalse(FeatureGate.canScheduleBackgroundMonitoring(Tier.FREE))
        assertTrue(FeatureGate.canScheduleBackgroundMonitoring(Tier.PRO))
    }

    @Test
    fun scheduledReportsFollowBackgroundMonitoring() {
        // Reports are built from background data, so gating them differently
        // would either promise reports with nothing to report on, or withhold
        // reports from data already collected.
        assertEquals(
            FeatureGate.canScheduleBackgroundMonitoring(Tier.FREE),
            FeatureGate.canReceiveScheduledReports(Tier.FREE),
        )
        assertEquals(
            FeatureGate.canScheduleBackgroundMonitoring(Tier.PRO),
            FeatureGate.canReceiveScheduledReports(Tier.PRO),
        )
    }

    @Test
    fun exportIsFreeAtEveryTier() {
        // It is a record of the user's own connection, collected on their own
        // device. Holding it hostage would be indefensible.
        assertTrue(FeatureGate.canExport(Tier.FREE))
        assertTrue(FeatureGate.canExport(Tier.PRO))
    }

    @Test
    fun lapsingNeverDeletesCollectedData() {
        assertTrue(FeatureGate.retainDataOnLapse())
    }

    @Test
    fun proRetainsLongerThanFree() {
        assertTrue(FeatureGate.retentionDays(Tier.PRO) > FeatureGate.retentionDays(Tier.FREE))
    }

    @Test
    fun freeOffersNoIntervalsRatherThanDisabledOnes() {
        // An empty list means the UI cannot accidentally present a value the
        // scheduler would refuse.
        assertTrue(FeatureGate.allowedIntervalMinutes(Tier.FREE).isEmpty())
        assertTrue(FeatureGate.allowedIntervalMinutes(Tier.PRO).isNotEmpty())
    }

    @Test
    fun everyProIntervalRespectsWorkManagerFloor() {
        // WorkManager silently clamps below 15 minutes, so offering 5 would be
        // advertising a cadence the platform will not honour.
        assertTrue(FeatureGate.allowedIntervalMinutes(Tier.PRO).all { it >= 15 })
    }
}

class PaywallTest {

    @Test
    fun proHasNothingLocked() {
        assertTrue(Paywall.lockedFor(Tier.PRO).isEmpty())
    }

    @Test
    fun everyLockedFeatureExplainsItself() {
        // A disabled control with no reason is the most annoying possible
        // paywall; the UI needs something to actually say.
        val locked = Paywall.lockedFor(Tier.FREE)
        assertTrue(locked.isNotEmpty())
        assertTrue(locked.all { it.name.isNotBlank() && it.reason.isNotBlank() })
    }

    @Test
    fun freeTierAdvertisesWhatItActuallyKeeps() {
        assertTrue(Paywall.freeAlwaysIncludes().isNotEmpty())
    }
}

class RetentionRatchetTest {

    @Test
    fun freeUserWhoNeverPaidGetsTheShortWindow() {
        assertEquals(
            FeatureGate.retentionDays(Tier.FREE),
            FeatureGate.effectiveRetentionDays(Tier.FREE, hasEverHadPro = false),
        )
    }

    @Test
    fun lapsedProKeepsTheLongWindow() {
        // The bug this guards: pruning a lapsed user back to the free window
        // would delete months of history on the next cycle — exactly what
        // retainDataOnLapse() promises never happens. Disk is the cheaper cost.
        assertEquals(
            FeatureGate.retentionDays(Tier.PRO),
            FeatureGate.effectiveRetentionDays(Tier.FREE, hasEverHadPro = true),
        )
    }

    @Test
    fun retentionNeverShrinksWhenProLapses() {
        val whilePro = FeatureGate.effectiveRetentionDays(Tier.PRO, hasEverHadPro = true)
        val afterLapse = FeatureGate.effectiveRetentionDays(Tier.FREE, hasEverHadPro = true)
        assertTrue(afterLapse >= whilePro, "retention shrank on lapse: $whilePro -> $afterLapse")
    }
}
