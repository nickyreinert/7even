# 7even Mobile — Plan for an Android + iOS background connection monitor

Status: proposal, nothing built yet. Researched August 2026.

**Recommendation in one line:** Kotlin Multiplatform, Android first — a normal
Kotlin/Compose Android app whose measurement engine lives in a pure-Kotlin
module that is already portable, so iOS later is a port rather than a rewrite.
Cordova is rejected for the background half (§3), and iOS will never do
continuous background monitoring no matter what we build (§2).

## 1. What we want

Take what `index.html` already does — latency, jitter, request loss, download,
upload, drop detection, stability score — and run it **unattended in the
background** on a phone, then hand the user a **report every day / week /
month**. Configurable intervals. Android first, iOS later.

## 2. Read this before anything else

The single most important finding of this research:

> **"Constantly monitors in the background" is achievable on Android and is
> not achievable on iOS.** Not "hard on iOS" — not permitted by the OS, at any
> price, for any framework.

This is not a framework choice we can engineer around. Both platforms killed
arbitrary background execution years ago, and they did it at the OS scheduler
level, below where any cross-platform toolkit sits. A Flutter app, a React
Native app, a Cordova app and a pure Swift app all get the *same* background
budget, because the budget is enforced by the OS against the process, not
against the language.

What each platform will actually give us:

| | Android | iOS |
|---|---|---|
| Periodic wakeups | `WorkManager`, **min 15 min** interval, fairly reliable | `BGAppRefreshTask`, **no guaranteed schedule at all** |
| Time per wakeup | ~10 min (job), longer w/ foreground service | **~30 s**, then killed |
| Continuous running | Yes — foreground service + persistent notification | No. Full stop. |
| Practical samples/day | 96 at 15-min cadence | maybe 2–6, system's choice |
| Survives force-quit | Yes (WorkManager reschedules) | No — background refresh stops entirely |

Sources for those numbers are listed in §11.

### What this means for the product

We should stop describing this as "a monitor that runs constantly" and start
describing it as:

- **Android:** a genuine continuous monitor. 15-minute sampling by default, and
  an optional "intensive session" mode with a persistent notification for
  users actively diagnosing a problem.
- **iOS:** an opportunistic sampler. It collects what the OS lets it collect,
  is honest in the UI that coverage is partial, and leans on foreground
  sessions (user opens the app and runs a test) plus whatever background
  crumbs iOS grants.

If we ship iOS pretending it samples continuously, users will compare the
report's gaps against reality and conclude the app is broken. The honesty is
not a nice-to-have; it's the difference between a 2-star and a 4-star app.

## 3. Does Cordova work for this? — No. Here's the evidence.

Direct answer to the question asked: **Cordova is the wrong tool here, and so
is a WebView-based approach generally, for the background half of the app.**

Three independent reasons:

**3.1 The WebView is suspended in the background.** Cordova runs the app inside
a system WebView. When the app leaves the foreground, the OS suspends that
WebView — timers stop, sockets die. Any background work has to be handed to a
*native* plugin running outside the WebView. So the moment we want background
monitoring, we are writing native code anyway, and Cordova has bought us
nothing for the hard part.

**3.2 `cordova-plugin-background-mode` is a policy trap.** The plugin's own
maintainers state that infinite background execution "is not officially
supported by Google Play or Apple App Store" and that a successful submission
is "possible but not guaranteed." The way it works on Android is by holding a
foreground service with a persistent notification — which is a legitimate
technique, but one we can use directly and more cleanly ourselves. On iOS the
plugin's approach is essentially a workaround that App Review has been
tightening against for years.

**3.3 The modern Capacitor equivalent can't run our measurement.** Capacitor
(the actively-maintained successor to Cordova, same authors as Ionic) has a
purpose-built `@capacitor/background-runner`. I checked its runtime capabilities
directly, and it is disqualifying for us:

- **No `WebSocket`.** No `XMLHttpRequest` either. Only a reduced `fetch`.
- Our entire throughput engine — `ws-speedtest`, `down_start`/`up_start`
  rounds, the whole `src/index.js` protocol — is WebSocket-based. It cannot
  run in that runtime at all.
- ~30 s cap on iOS, and "state is not maintained between calls" — each wakeup
  is a cold context.

So even the best-supported WebView-family option requires us to rewrite the
measurement protocol *and* still leaves us with a 30 s iOS ceiling.

**Where Cordova/Capacitor *is* still useful:** as the **foreground UI shell**.
`index.html` is a complete, working, debugged UI. Wrapping it in Capacitor to
get the charts, history, settings, and report views on a phone is a legitimate
and fast path. The recommendation below does exactly that — it just refuses to
put the background measurement inside the WebView.

## 4. Framework options compared

Given the decision to **go fully native and start with Android**, the tradeoff
changes. The main argument for a WebView shell was reusing `index.html`; if
Phase 1 is Android-only, we write a native UI for it either way.

| Option | Background engine | UI | Verdict |
|---|---|---|---|
| **A. Kotlin Multiplatform** | shared Kotlin engine; `WorkManager` on Android, called from `BGTaskScheduler` on iOS | native Compose (+ Compose MP or SwiftUI later) | **Recommended** |
| B. Full native ×2 | Kotlin + Swift, written twice | Compose + SwiftUI | Fine, but A is this with the duplication removed |
| C. Flutter | `workmanager` plugin; background isolate has full `dart:io` | Flutter widgets | Viable, but not "native", and Dart is a second ecosystem |
| D. Capacitor shell + native plugin | native plugin per platform | reuse `index.html` | Fastest to an Android MVP, weakest long-term |
| E. React Native | Headless JS is **Android-only** | RN | Worst cross-platform story for *this* problem |
| F. Cordova + background-mode | policy trap, WebView suspended | reuse `index.html` | **Rejected** — see §3 |

### Recommendation: Kotlin Multiplatform (KMP)

This is precisely the "fully native but cross-platform" shape you described,
and it fits this problem unusually well:

- **The shared part is exactly the part we'd otherwise duplicate.** Our
  measurement engine is pure logic — timed HTTP requests, byte counting,
  rolling statistics, the stability score. It touches no UI. In KMP that
  becomes one Kotlin module compiled natively for both platforms. This removes
  the "maintain Kotlin and Swift measurement parity" risk from §11 entirely,
  which was the main weakness of the previous recommendation.
- **The UI stays genuinely native.** Compose on Android now; at iOS time we
  choose Compose Multiplatform (stable for iOS since May 2025) or SwiftUI.
  That decision can be deferred without blocking anything.
- **Nothing is abstracted away.** Unlike a WebView or Flutter, platform APIs
  are directly reachable via `expect`/`actual` — `WorkManager`,
  `ConnectivityManager`, `NWPathMonitor`, `BGTaskScheduler` are all first
  class, not waiting on a plugin author.
- **It is production-proven and Google-endorsed** as of 2026, not a bet.

**The key practical point for an Android-first build:** Phase 1 does *not*
require standing up the whole KMP toolchain. It requires one discipline —
keep the measurement engine in a **pure-Kotlin module with zero Android
imports** (no `Context`, no `android.*`), talking to the rest of the app
through interfaces. That module is then already KMP-ready. Adding the iOS
target later becomes a build-configuration change plus writing the `actual`
implementations, instead of a rewrite.

So: build a normal Kotlin Android app, structured so the interesting half is
portable. Pay the KMP cost only when iOS actually arrives.

### Library choices

| Concern | Pick | Why |
|---|---|---|
| HTTP / WebSocket | **Ktor client** — OkHttp engine on Android, Darwin on iOS | Ktor's own recommended pairing; Darwin wraps `NSURLSession`, OkHttp is the Android standard |
| Persistence | **Room 3.x** (full KMP support) | Same API you'd use on Android anyway; near-zero learning curve. SQLDelight is the alternative if you'd rather write raw SQL |
| DI | Koin | Standard KMP choice, no codegen |
| Scheduling | `WorkManager` (Android) / `BGTaskScheduler` (iOS) via `expect`/`actual` | Deliberately *not* abstracted — the platforms differ too much to paper over |

### What happens to `index.html`

It stays. It is live at `7.1-1-1.de`, it works, and it keeps earning its place
as the zero-install way to run a check. It also becomes the **reference spec**
for the port: `runStreamTest()`, `runSizeSweep()`, `computeStabilityScore()`
and the drop-detection logic are the algorithms the Kotlin engine
reimplements, and its existing JSON export is the interchange format.

The charts are the main porting cost. `drawBarChart()`,
`drawValueAxisTicks()` and `renderLiveRateChart()` are written against the 2D
canvas API, which maps closely onto Compose's `Canvas` / `DrawScope` — the
translation is mechanical rather than a redesign.

## 5. Architecture

```
┌──────────────────────────────────────────────────────┐
│ UI — Compose (Android)  ·  Compose MP / SwiftUI later│
│  live charts · history · reports · settings          │
└───────────────┬──────────────────────────────────────┘
                │
┌───────────────▼──────────────────────────────────────┐
│ :shared  (pure Kotlin, KMP-ready, no platform imports)│
│  measurement engine · probe tiers · stats · reports   │
│  Room entities/DAOs · Ktor client                     │
└───────────────┬──────────────────────────────────────┘
                │ expect/actual
     ┌──────────┴───────────────────────┐
     │ Android: WorkManager + opt. FGS  │  iOS: BGTaskScheduler
     │ ConnectivityManager              │  NWPathMonitor
     └──────────────────────────────────┘
```

Key decisions baked into this:

- **`:shared` never imports a platform.** This is the whole discipline. If it
  compiles without an Android SDK on the classpath, iOS is cheap later. If it
  doesn't, we've silently chosen Option B and doubled the iOS cost.
- **Scheduling is deliberately not abstracted.** `WorkManager` and
  `BGTaskScheduler` have genuinely different semantics (guaranteed-ish
  periodic vs opportunistic). A common interface pretending otherwise would
  hide the one thing §2 says we must be honest about. The engine exposes
  "run one probe cycle"; each platform decides when to call it.
- **Room, not `localStorage`.** The current app persists to `localStorage`,
  which is per-WebView and unreachable from a background task. Room is the
  shared surface both the UI and the background worker read and write.
- **No backend required for v1.** Reports are computed on-device. A backend is
  needed only for iOS silent push (§7.2) and cross-device history — both
  deferred.

## 6. The measurement design has to change

This is the part most likely to be got wrong, so it gets its own section.

The current foreground test is **expensive**: 15 s of continuous downloading
plus 15 s of continuous uploading, plus a chunk sweep that pulls 32 KB → 10 MB.
One full cycle moves on the order of tens of megabytes.

Running that every 15 minutes would use **gigabytes of cellular data per
month** and cook the battery. It also cannot fit in iOS's 30 s window. So the
background probe must be a *different, much cheaper* measurement.

### Tiered probes

| Tier | What it does | Payload | When |
|---|---|---|---|
| **T1 — Reachability** | 3× HTTPS GET to `/cdn-cgi/trace`, record RTT + failures | ~3 KB | every cycle (15 min) |
| **T2 — Light throughput** | 256 KB down, 128 KB up, timed | ~400 KB | every 4th cycle (hourly), **Wi-Fi or explicitly allowed cellular** |
| **T3 — Full sweep** | today's chunk-size ladder + stream test | ~30 MB | Wi-Fi **and** charging only, ≤2×/day; or user taps "run now" |

T1 alone already produces latency, jitter, request loss and drop events — which
is most of what the weekly report is actually about. Throughput is the
expensive number, so it gets sampled sparsely and is presented in reports as
"sampled N times" rather than implied-continuous.

### Data budget (default settings)

| | per day | per 30 days |
|---|---|---|
| T1 @ 15 min | ~290 KB | ~8.7 MB |
| T2 @ hourly, Wi-Fi only | 0 on cellular | 0 on cellular |
| T3 @ 2/day, Wi-Fi+charging | 0 on cellular | 0 on cellular |

**Cellular cost with defaults: under 10 MB/month.** That is a number we can put
in the store listing. Every tier above T1 is opt-in per network type, and the
settings screen shows the projected monthly cost as the user changes the
sliders — the same "show your work" principle the web app already follows.

### Protocol: HTTP for background probes, WebSocket kept for the rest

Note this reasoning changed with the move to KMP. Under Capacitor it was
*forced* — that runtime has no `WebSocket` at all. Ktor has full WebSocket
support on both platforms, so this is now a **design choice, not a
constraint**, and the case is narrower:

- A long-lived socket is a poor fit for a 30 s iOS window and for a process
  that may be frozen mid-transfer. Short discrete requests resume cleanly.
- T1 is three tiny requests; opening a WebSocket to carry them costs more in
  handshake than it saves.

So: T1/T2 use plain HTTPS. **T3 and the Android foreground-service intensive
mode keep the existing WebSocket streaming protocol unchanged** — there, the
process is alive and unfrozen, and the continuous-stream measurement is the
better one. This is a real improvement over the previous plan, which would
have lost WebSocket streaming on mobile entirely.

For T1/T2, use plain HTTPS against the existing endpoints:

- down: `https://speed.cloudflare.com/__down?bytes=N` (already used by the sweep)
- up: extend the `ws-speedtest` Worker with a plain `POST /__up` that reads and
  discards the body and returns the byte count — a small addition to
  `src/index.js`, reusing the origin allowlist already there.

The WebSocket engine stays exactly as it is for foreground/T3 use.

## 7. Scheduling, per platform

### 7.1 Android

- `PeriodicWorkRequest`, 15 min, with `setRequiredNetworkType` constraints per
  tier. This is the backbone and it works well.
- `RECEIVE_BOOT_COMPLETED` to reschedule after reboot.
- **Intensive mode** (user-initiated, for active troubleshooting): a foreground
  service with `foregroundServiceType="dataSync"` and a persistent
  notification, sampling every ~30 s. Constraints to respect:
  - Android 14+ requires declaring the FGS type *and* filling out a Play
    Console declaration form.
  - Android 15+ caps `dataSync` at **6 hours per 24**, after which
    `onTimeout()` fires and we must stop cleanly and fall back to WorkManager.
  - Android 16 enforces JobScheduler quotas more strictly even for jobs
    started from an FGS.
  So: intensive mode is explicitly a *session* with a visible timer, not a
  permanent state. Cap it at 2 h by default.
- Battery optimization exemption: offer it via
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, **as an optional prompt for
  power users only**, never on first launch. Play policy prohibits requesting
  it unless core function is genuinely impaired, and over-requesting it is a
  known review flag.

### 7.2 iOS

- `BGAppRefreshTask` for T1. Re-submit a new request at the *start* of every
  execution (a fired task is consumed). Expect a handful of runs/day; frequency
  is tied to how often the user actually opens the app.
- `BGProcessingTask` with `requiresExternalPower = true` for T3 — this is the
  one place iOS is generous, because it runs while charging overnight.
- Only **1 refresh + 10 processing** requests may be pending at once. Budget
  accordingly.
- **Silent push** (`content-available: 1`) is the only lever that meaningfully
  increases iOS wakeups, but it is *also* throttled — realistically a handful
  per device per day, dropped entirely once the device's daily background
  budget is spent, and explicitly documented by Apple as best-effort. It needs
  a backend + APNs. **Deferred to Phase 4**, and even then framed as "improves
  coverage," never "guarantees it."
- The UI must show honest coverage: "14 of an expected 96 samples collected
  today — iOS limits background measurement." Turn the limitation into a
  visible, explained data point rather than a silent gap. This fits the app's
  existing character (it already annotates partial results and non-standard
  metrics).

## 8. Reports

The actual product deliverable. Computed on-device from SQLite rollups.

- **Cadence:** daily / weekly / monthly, user-selectable, delivered as a local
  notification that opens a report view.
- **Content:** uptime %, drop events with timestamps and durations, latency
  p50/p95, jitter, loss %, throughput samples (count + median, clearly labeled
  as sampled), a per-hour heatmap of quality, and best/worst periods.
- **Segment by network.** A report that mixes home Wi-Fi with commuting LTE is
  noise. Group by connection type at minimum. *Caveat:* reading the Wi-Fi SSID
  to label networks requires location permission on both platforms — that is a
  meaningful privacy ask, so v1 groups by *type* (wifi/cellular) only, and SSID
  labeling is an explicit opt-in later.
- **Export:** reuse the existing JSON export; add CSV and a shareable PDF/PNG
  summary.
- Reuse `computeStabilityScore()` unchanged, including its "not a standard
  metric, here are its inputs" framing — that transparency is a genuine
  differentiator against Ookla-style black-box scores.

## 8a. Free vs paid

**The line: one-off testing is free, unattended repeated testing is paid.**

That is the honest place to draw it. A single manual test costs nothing per run
— the measurement is client-side against public endpoints — so charging for it
would be charging for nothing. What genuinely costs something is *repeated
background measurement*: it is the part that takes ongoing work to keep
reliable across OS versions and OEM battery quirks, and the part that consumes
the user's battery and data.

| | Free | Pro |
|---|---|---|
| Run a test on demand | ✅ unlimited | ✅ |
| Live latency / jitter / loss / speed while open | ✅ | ✅ |
| Export your data | ✅ | ✅ |
| **Background monitoring on a schedule** | ❌ | ✅ |
| **Daily / weekly / monthly reports** | ❌ | ✅ |
| History retention | 2 days | 90 days |

Three rules that are implemented and tested, not just intended:

1. **Export is free at every tier.** The data is a record of the user's own
   connection, collected on their own device. Holding it hostage behind a
   subscription would be indefensible.
2. **Lapsing never deletes collected history.** Collection stops; the record
   stays readable and exportable. The retention window *ratchets* — once Pro
   has been held, the 90-day window sticks, because dropping a lapsed user to
   2 days would delete months of history on the next prune. Costs some disk;
   far better than destroying a record that cannot be recreated.
3. **A few days of grace after a failed renewal.** A card that fails to renew
   is usually a payment glitch, not a decision to quit. Cutting monitoring off
   instantly puts a *gap in the data* — the one thing the user cannot go back
   and recreate. The grace window is for their data's sake, not as a sales
   tactic.

Enforcement is at the scheduling layer, not just the UI: `ProbeWorker` and
`ReportWorker` re-check entitlement on every wakeup, so a lapse stops
collection without needing the app to be opened. Hiding a switch is
presentation; that is the gate.

### Billing is not built

`EntitlementRepository` currently stores whatever it is told and has no
connection to Google Play. Selling a subscription requires the Play Billing
Library **plus server-side verification of the purchase token** — Play policy
requires Play Billing for digital goods, and a client-side-only entitlement is
trivially defeated. The Upgrade button says so rather than pretending.

What *is* decided and tested is the part worth deciding carefully: what is
free, what lapsing does to the user's data, how grace behaves. Swapping the
entitlement's source from local to verified-purchase is a contained change.

## 9. Store policy notes

- **Apple:** background modes may "only be used for their intended purposes."
  A network-quality monitor using `BGAppRefreshTask` for its stated purpose is
  legitimate — but the review notes must explain plainly what runs in the
  background and why. Do not attempt background-location or audio tricks to
  gain execution time; that is the classic rejection.
- **Google:** declare the FGS type, complete the Play Console declaration, and
  do not request battery-optimization exemption by default.
- **Both:** disclose data usage prominently. An app that silently consumes
  cellular data is a review and a review-score problem.

## 10. Phased roadmap

| Phase | Scope | Rough effort |
|---|---|---|
| **0. Spike** | Kotlin Android app; `:shared` module skeleton; Room; a `WorkManager` job that wakes and records T1 overnight on a real device | 1 week |
| **1. Android MVP** | T1+T2 in `:shared`, Compose UI incl. ported charts, config, drop detection, daily/weekly report + notification, boot persistence | 4–6 weeks |
| **2. Android polish** | Intensive-mode FGS w/ Android 15 timeout handling, T3 over WebSocket, per-network segmentation, export, data-budget UI | 2–3 weeks |
| **3. iOS via KMP** | add iOS target to `:shared`, write `actual`s, `BGTaskScheduler` host, UI (Compose MP or SwiftUI), honest coverage UI, App Review notes | 3–4 weeks |
| **4. Optional** | Silent-push backend for iOS coverage, cross-device sync, SSID labeling | open |

Phase 1 is ~1–2 weeks longer than the Capacitor path because the UI is new
rather than reused. Phase 3 is correspondingly cheaper and much lower-risk,
because the engine is already shared and tested rather than being reimplemented
in Swift. Net effort is roughly a wash; the KMP version ends up with the better
codebase.

**Phase 0 is a genuine go/no-go gate.** If a `WorkManager` job does not
reliably fire overnight on a real device under real OEM battery management
(Samsung One UI and Xiaomi HyperOS are notoriously aggressive), the entire
premise needs revisiting before spending the other ~10 weeks. Test on a
physical Samsung or Xiaomi, not an emulator — emulators do not reproduce OEM
battery killers, which is exactly the failure mode that matters.

## 11. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| iOS background is too sparse to be useful | **High** | Set expectations in UI/listing; lean on charging-time `BGProcessingTask`; consider push in Ph.4 |
| OEM battery killers (Samsung/Xiaomi/Huawei) drop WorkManager jobs | **High** | Test on real devices in Ph.0; offer the exemption prompt; detect and surface missed-sample gaps |
| Cellular data complaints | Medium | Tiered probes, Wi-Fi-only defaults, projected-cost UI |
| Measuring the network changes the network | Medium | Keep T1 tiny; never run T2/T3 concurrently with itself; already handled in sweep design |
| Play/App Review rejection over background use | Medium | Declare properly, no tricks, clear reviewer notes |
| ~~Maintaining Kotlin+Swift measurement parity~~ | — | **Eliminated by KMP** — one shared engine, not two implementations |
| `:shared` accidentally takes an Android dependency, silently killing the KMP payoff | Medium | Enforce from Phase 0: `:shared` must compile with no Android SDK on the classpath; make that a CI check, not a code-review habit |
| Compose chart porting takes longer than expected | Low-Med | Charts are the one genuinely fiddly port; budget for them explicitly in Phase 1 rather than treating them as UI trim |

## 12. Open questions for you

Two of the four are now settled: **Android first** (confirmed), and the
**WebSocket question is moot** — Ktor supports it on both platforms, so T3
and intensive mode keep the existing streaming protocol.

Still open:

1. **Personal tool or store product?** This is now the biggest open decision,
   and it changes the design materially. A personal-use Android app can take a
   battery-optimization exemption and run a permanent foreground service
   freely, sample every 30 s, and skip all of §9 — which makes the product
   dramatically better. A store product cannot.
2. **Report destination** — local notification only, or also email/webhook?
   Email implies a backend.
3. **iOS UI at Phase 3** — Compose Multiplatform (share the UI too) or SwiftUI
   (fully native feel)? Genuinely deferrable; noting it so it isn't forgotten.

If the answer to (1) is "personal tool," say so early — it would let us cut
most of §9, loosen §6's data budget, and get to something useful in
noticeably less than the estimate above.

## Sources

- [Foreground service timeouts — Android Developers](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Behavior changes: Android 15+ — Android Developers](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Changes to foreground services — Android Developers](https://developer.android.com/develop/background-work/services/fgs/changes)
- [Behavior changes: all apps (Android 16) — Android Developers](https://developer.android.com/about/versions/16/behavior-changes-all)
- [Optimize for Doze and App Standby — Android Developers](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Background Runner Capacitor Plugin API — Capacitor Docs](https://capacitorjs.com/docs/apis/background-runner)
- [ionic-team/capacitor-background-runner — GitHub](https://github.com/ionic-team/capacitor-background-runner)
- [cordova-plugin-background-mode — GitHub](https://github.com/globules-io/cordova-plugin-background-mode)
- [iOS Background Execution Limits: What Every Developer Must Know (2026)](https://www.appsonair.com/blogs/background-execution-limits-in-ios-what-every-developer-must-know)
- [Swift iOS BackgroundTasks framework — ITNEXT](https://itnext.io/swift-ios-13-backgroundtasks-framework-background-app-refresh-in-4-steps-3da32e65bc3d)
- [Silent Push Notifications in iOS: Opportunities, Not Guarantees](https://mohsinkhan845.medium.com/silent-push-notifications-in-ios-opportunities-not-guarantees-2f18f645b5d5)
- [App Review Guidelines — Apple Developer](https://developer.apple.com/app-store/review/guidelines/)
- [Background processes — Flutter docs](https://docs.flutter.dev/packages-and-plugins/background-processes)
- [Run React Native background tasks with Headless JS — LogRocket](https://blog.logrocket.com/run-react-native-background-tasks-headless-js/)
- [Clarification on FOREGROUND_SERVICE_DATA_SYNC and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — Google Play Developer Community](https://support.google.com/googleplay/android-developer/thread/330168645/clarification-on-foreground-service-data-sync-and-request-ignore-battery-optimizations?hl=en)
- [Kotlin Multiplatform — Android Developers](https://developer.android.com/kotlin/multiplatform)
- [Kotlin Multiplatform — kotlinlang.org](https://kotlinlang.org/multiplatform/)
- [Compose Multiplatform — kotlinlang.org](https://kotlinlang.org/compose-multiplatform/)
- [Compose Multiplatform for iOS Stable in 2025](https://www.kmpship.app/blog/compose-multiplatform-ios-stable-2025)
- [Is Kotlin Multiplatform production-ready in 2026? — Volpis](https://volpis.com/blog/is-kotlin-multiplatform-production-ready/)
- [Set up Room database for KMP — Android Developers](https://developer.android.com/kotlin/multiplatform/room)
- [Room vs SQLDelight for Kotlin Multiplatform (2026)](https://docs.bswen.com/blog/2026-03-14-room-vs-sqldelight-kmp/)
- [Ktor client engines — Ktor Documentation](https://ktor.io/docs/client-engines.html)
