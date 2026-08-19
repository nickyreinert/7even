# 7even Mobile

Android-first background connection monitor. Design and platform research live
in [`../app.md`](../app.md); this file covers the code.

## Status — read this first

| Part | State | Verified how |
|---|---|---|
| `:shared` measurement engine | **working** | 56 unit tests, green on JDK 21 |
| `checkNoPlatformImports` gate | **working** | verified to fail on a deliberate `import android.*` |
| `androidApp` module | **written, never compiled** | ⚠️ no Android SDK in this environment |

`androidApp` is a skeleton written against the APIs, not a build that has ever
run. **Assume it does not compile until someone opens it in Android Studio.**
Expect import and dependency fixes on first build; the architecture is the part
worth reviewing, not the syntax.

`:shared` is the opposite — it genuinely compiles and its tests genuinely pass,
which is why it holds all the logic worth trusting.

## Layout

```
mobile/
├── settings.gradle.kts        :androidApp is commented out on purpose (see below)
├── shared/                    pure Kotlin, KMP-ready, fully tested
│   └── src/commonMain/kotlin/de/sevenapp/monitor/
│       ├── core/              models, stats, stability score, drop detection
│       ├── probe/             transport interface, tier policy, engine, data budget
│       └── report/            daily/weekly/monthly report building
└── androidApp/                WorkManager + Ktor + UI  ⚠️ uncompiled
```

## Running the tests

```bash
cd mobile
./gradlew :shared:check
```

That runs the unit tests *and* the platform-import gate. No Android SDK, no
Xcode, no device needed — which is the point.

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

## Why `:androidApp` is excluded from `settings.gradle.kts`

So `./gradlew check` stays green on a machine without the Android SDK. The
shared engine is the part that must always build; adding the app module to the
build on an SDK-less machine would fail at configuration time and take the
tests down with it. Uncomment the `include(":androidApp")` line once you have
a local SDK.

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

## Not built yet

- Room persistence — `MonitorRepository` is referenced by `ProbeWorker` but not
  written. It is the next thing to do, and the shape it must satisfy is fully
  determined by `ProbeEngine.CycleInput` / `CycleOutput`.
- Compose UI, including the chart port from `index.html`
- `THROUGHPUT_FULL` — the sweep ladder and WebSocket stream test. The tier
  exists and is selected correctly; it currently runs the light measurement.
  Ktor supports WebSocket on both platforms, so the existing protocol carries
  over unchanged.
- Everything iOS.

## Next step

Phase 0's real question is not whether this code works — it is whether
`WorkManager` actually fires overnight under OEM battery management. Test on a
**physical Samsung or Xiaomi**; emulators do not reproduce the aggressive
battery killers that are the single biggest risk to the premise.
