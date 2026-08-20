# 7even Mobile

Android-first background connection monitor. Design and platform research live
in [`../app.md`](../app.md); this file covers the code.

## Status — read this first

| Part | State | Verified how |
|---|---|---|
| `:shared` measurement engine | **working** | 160 unit tests, green on JDK 17 |
| `checkNoPlatformImports` gate | **working** | verified to fail on a deliberate `import android.*` |
| `androidApp` module | **working** | debug APK builds; `lintDebug` clean; host unit tests green |

Both modules build and their tests run in CI (`.github/workflows/deploy.yml`),
which gates every deploy. `:shared` still holds all the logic worth trusting —
it is the part that is portable and exhaustively tested — while `androidApp`
holds the platform glue that can only be exercised on a device.

Building `androidApp` needs a local Android SDK. `mobile/local.properties` is
gitignored; copy [`local.properties.example`](local.properties.example) and
fill it in.

## Layout

```
mobile/
├── settings.gradle.kts        both modules
├── shared/                    pure Kotlin, KMP-ready, fully tested
│   └── src/commonMain/kotlin/de/sevenapp/monitor/
│       ├── core/              models, stats, stability score, drop detection
│       ├── probe/             transport interface, tier policy, engine, data budget
│       └── report/            daily/weekly/monthly report building
└── androidApp/                WorkManager + Ktor + Compose UI, host unit tests
```

## Running the tests

```bash
cd mobile
./gradlew :shared:check                     # engine tests + platform-import gate
./gradlew :androidApp:testDebugUnitTest     # host-side Android tests
./gradlew :androidApp:lintDebug             # manifest/API lint
```

`:shared:check` runs the unit tests *and* the platform-import gate, with no
Android SDK, no Xcode and no device needed — which is the point. The
`:androidApp` tasks need a local SDK.

## The one rule

**`commonMain` must not import a platform.** Not `android.*`, not `java.*`,
not `platform.*`. This is what makes iOS a port rather than a rewrite later,
and it is exactly the kind of rule that quietly rots under deadline pressure —
one innocent `java.util.Date` and the module is no longer portable.

So it is enforced by the build, not by code review:

```
> Task :shared:checkNoPlatformImports FAILED
  commonMain must not depend on a platform — found 1 violation(s):
    kotlin/de/sevenapp/monitor/_GateProbe.kt:2: import android.content.Context
```

`check` depends on it, so CI catches it automatically.

Practical consequences you will hit immediately:
- no `System.currentTimeMillis()` — inject [`Clock`](shared/src/commonMain/kotlin/de/sevenapp/monitor/core/Model.kt)
- no `java.net`/`OkHttp` — go through [`Transport`](shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/Transport.kt)
- no `android.util.Log` — return values, let the host log them

All three restrictions are why the engine is testable at all.

## Why `:shared` is kept separately runnable

`./gradlew :shared:check` deliberately needs nothing but a JDK. The shared
engine is the part that must always build, and keeping its test run free of the
Android SDK means a contributor — or a CI job — can verify the measurement
logic without a 3GB toolchain. `:androidApp` is in the build; just don't reach
for it when all you changed was the engine.

## Design decisions worth knowing before editing

**The engine does not schedule itself.** `ProbeEngine.runCycle()` runs exactly
one cycle and returns. WorkManager (near-guaranteed, periodic) and
BGTaskScheduler (purely opportunistic) differ too fundamentally to hide behind
a shared "scheduler" interface, and pretending otherwise would obscure the
single most important fact about this product — that iOS cannot monitor
continuously. Each platform decides *when*; the engine decides *what*.

**The engine is stateless across cycles.** Background processes are killed
constantly. Anything that must survive — the drop detector's open drop, the
cycle counter, today's sweep count — is persisted and passed back in via
`CycleInput`. `DropDetector.restore()` exists for exactly this; without it a
two-hour outage would be recorded as eight unrelated 15-minute drops.

**Statistics match the web app deliberately, including the debatable parts.**
`stdDev` is the *population* standard deviation (÷n), not the sample one, and
not RFC 3550 inter-arrival jitter. It is what `index.html` has always shown and
what the stability score's 50/30/20 weighting is calibrated against. A phone
that disagreed with the browser about what "jitter" means would make
week-over-week comparison meaningless. Tests pin this so it cannot be
"corrected" by accident.

**A cycle with no network still records a failed probe.** Skipping it would let
the report claim uptime it never observed — the exact event a connection
monitor exists to catch.

**Throughput is skipped when every ping in the cycle failed.** Spending 400KB
measuring the speed of a link that just failed three reachability probes wastes
data and produces a meaningless number.

## Data budget

The default config is asserted by a test to stay under **10MB of cellular per
month**, because that number is intended for the store listing:

```kotlin
@Test fun defaultConfigStaysUnderTenMegabytesOfCellularPerMonth()
```

It holds because throughput tiers are gated to Wi-Fi by default. If you change
a default and that test fails, the fix is the default, not the test.

## What is here

`:shared` (all tested):
- `core/` — models, `Stats`, `StabilityScore`, `DropDetector`, `Format`
- `probe/` — `Transport` interface, `TierPolicy`, `ProbeEngine`, `DataBudget`,
  `SweepPlan`/`SweepRunner`/`SweepVerdict`, `MonitorCoordinator`
- `report/` — `ReportBuilder`, `ReportSchedule`
- `chart/` — `AxisTicks` (the fiddly tick-collision math, tested rather than
  eyeballed inside a draw block)
- `data/` — `MonitorStore`, the persistence contract
- `entitlement/` — `Tier`, `Entitlement`, `FeatureGate`, `Paywall`

`androidApp`:
- Room schema + `RoomMonitorStore` implementing `MonitorStore`
- `KtorTransport` (OkHttp engine)
- `ProbeWorker` (WorkManager), `ReportWorker` (report + notification),
  `BootReceiver`
- Compose dashboard + settings screen, their view models, chart port in
  `ui/Charts.kt`
- `EntitlementRepository` (local cache only — no billing)

## Free vs paid

> **The paywall is currently OFF** (`PaywallConfig.ENABLED = false`) and
> everyone resolves to Pro. Play's closed-testing requirement — 12 opted-in
> testers for 14 continuous days — means testers must be able to exercise
> background monitoring, which is the very thing the paywall locks. Switch it
> on once Play Billing is integrated; both states are tested.
>
> `FeatureGate.resolveTier()` is the single resolution point. Everything goes
> through it, so the flag really is one switch.

**The intended line: one-off testing is free; unattended repeated testing is Pro.** A manual test
costs nothing per run — the measurement is client-side against public
endpoints — so charging for it would be charging for nothing. Pro is the
scheduled, unattended version, plus reports and 90-day history.

Three rules are implemented and tested, not merely intended:

- **Export is free at every tier** (`FeatureGate.canExport`). It is the user's
  own data from their own device.
- **Lapsing never deletes history.** Retention *ratchets*:
  `effectiveRetentionDays(tier, hasEverHadPro)` keeps the 90-day window once
  Pro has been held, because dropping a lapsed user to the 2-day free window
  would delete months of history on the next prune.
- **Three days of grace after a failed renewal**, so a payment glitch does not
  punch a gap in the data.

Enforcement is at the scheduling layer: `ProbeWorker` and `ReportWorker`
re-check entitlement on every wakeup and cancel themselves if it lapsed. Hiding
a switch is presentation; that is the gate.

⚠️ **Billing is not implemented.** `EntitlementRepository` stores whatever it is
told and has no connection to Google Play. Real selling needs the Play Billing
Library plus server-side purchase-token verification — a client-side-only
entitlement is trivially defeated. The Upgrade button says so rather than
pretending. What *is* decided and tested is the part worth deciding carefully:
what is free, what lapsing does to data, how grace behaves.

## Not built yet

- **Play Billing**, and with it flipping `PaywallConfig.ENABLED`. Note the
  ordering trap: while the paywall is off everyone banks 90-day retention, so
  `EntitlementRepository.effectiveTier()` records the Pro high-water mark as it
  resolves. Without that, switching the paywall on would prune existing users'
  history back to 2 days. It is covered by
  `switchingThePaywallOnMustNotPruneHistoryCollectedBeforeIt`.
- **`THROUGHPUT_FULL` is selected but not implemented.** `TierPolicy` returns it
  correctly and `SweepRunner` exists and is tested, but `ProbeEngine` still runs
  the *light* measurement for that tier — the sweep and the WebSocket stream
  test are not wired into the cycle yet. Wiring them is the next real task.
- Export writes nothing yet — `SettingsViewModel.export()` is a stub. The
  button is present because export being free is a stated promise; it needs the
  storage-access-framework plumbing behind it.
- Everything iOS.

## Next step

Two things, in order:

1. **Open it in Android Studio and make it compile.** It never has. Expect
   import and dependency fixes; the architecture is what to review, not the
   syntax.
2. **Then the question that actually matters:** does `WorkManager` fire
   overnight under OEM battery management? Test on a **physical Samsung or
   Xiaomi** — emulators do not reproduce the aggressive battery killers that
   are the single biggest risk to this whole premise. No amount of passing unit
   tests answers that one.
