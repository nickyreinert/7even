# 7even Mobile — Plan for an Android + iOS background connection monitor

Status: proposal, nothing built yet. Researched August 2026.

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

| Option | Background engine | UI | Verdict |
|---|---|---|---|
| **A. Capacitor shell + native measurement plugin** | Kotlin `WorkManager` + Swift `BGTaskScheduler`, written by us | reuse `index.html` as-is | **Recommended** |
| B. Flutter rewrite | `workmanager` plugin, background isolate has full `dart:io` (real sockets) | rewrite from scratch | Good engineering, throws away working UI |
| C. Full native ×2 | ideal control | 2× UI work | Only if the app becomes a serious product |
| D. React Native | Headless JS is **Android-only**; iOS needs a separate path | reuse some web skills | Worst cross-platform story for *this* problem |
| E. Cordova + background-mode | policy trap, WebView suspended | reuse `index.html` | **Rejected** — see §3 |

### Recommendation: Option A

Reasoning:

- The background measurement engine we need is **small**. It is a handful of
  timed HTTP requests and a byte-counting loop — a few hundred lines of Kotlin
  and a few hundred of Swift. Writing it twice natively is cheaper than
  rewriting the entire UI in Dart to avoid writing it twice.
- The UI is the expensive, already-paid-for part. `index.html` is ~2,500 lines
  of working, iterated-on interface. Capacitor lets us keep all of it.
- Native gives us direct access to the things that matter here and that
  wrappers abstract away badly: `NWPathMonitor` / `ConnectivityManager` for
  network-type transitions, `URLSession` background transfers, precise
  `WorkManager` constraints (unmetered-only, charging-only).
- If Flutter later looks better, the measurement engine's *design* (§6) ports
  directly; only the host changes.

**Choose Flutter instead if** we expect to grow well beyond this feature set,
or if maintaining parallel Kotlin/Swift measurement code proves annoying in
practice. Flutter's background isolate runs a full Dart VM, so unlike the
Capacitor runner it *can* open a real WebSocket. That is a genuine advantage —
it just doesn't outweigh rewriting the UI today.

## 5. Architecture

```
┌──────────────────────────────────────────────────────┐
│ Foreground UI  —  index.html in a Capacitor WebView  │
│  live charts · history · reports · settings          │
└───────────────┬──────────────────────────────────────┘
                │ Capacitor bridge (read samples, write config)
┌───────────────▼──────────────────────────────────────┐
│ Shared local store — SQLite                          │
│  samples · drop events · rollups · config            │
└───────────────▲──────────────────────────────────────┘
                │ writes samples
┌───────────────┴──────────────────────────────────────┐
│ Native measurement engine  (Kotlin | Swift)          │
│  probe scheduler · HTTP timing · byte counting       │
└───────────────┬──────────────────────────────────────┘
                │
     ┌──────────┴──────────┐
     │ Android: WorkManager│  iOS: BGTaskScheduler
     │ + optional FGS      │  + silent push (best effort)
     └─────────────────────┘
```

Key decisions baked into this:

- **SQLite, not `localStorage`.** The current app persists to `localStorage`,
  which is per-WebView and unreachable from a native background task. The
  background engine must write somewhere the UI can read. SQLite is the shared
  surface. The existing JSON snapshot format maps onto it cleanly.
- **The UI never measures in the background.** It only measures while
  on-screen (where the existing WebSocket engine works fine, unchanged). All
  unattended measurement is native.
- **No backend required for v1.** Reports are computed on-device from local
  samples. A backend becomes necessary only for iOS silent push (§7.2) and for
  cross-device history — both explicitly deferred.

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

### Protocol change: drop WebSocket for background probes

Background runtimes handle short, discrete HTTP requests far better than
long-lived sockets — a socket that has to survive a 30 s iOS window and a
process that may be frozen mid-transfer is a bad fit. For T1/T2, use plain
HTTPS against the existing endpoints:

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
| **0. Spike** | Capacitor shell + `index.html` running on a device; SQLite bridge; prove a Kotlin `WorkManager` job wakes and records T1 overnight | 1 week |
| **1. Android MVP** | T1+T2 tiers, config UI, drop detection, daily/weekly report + notification, boot persistence | 3–4 weeks |
| **2. Android polish** | Intensive-mode FGS w/ Android 15 timeout handling, T3, per-network segmentation, export, data-budget UI | 2–3 weeks |
| **3. iOS** | `BGTaskScheduler` engine (Swift port of the Kotlin engine), honest coverage UI, App Review notes | 3–4 weeks |
| **4. Optional** | Silent-push backend for iOS coverage, cross-device sync, SSID labeling | open |

Phase 0 is genuinely a go/no-go gate. If a `WorkManager` job does not reliably
fire overnight on a real device with real OEM battery management (Samsung One
UI and Xiaomi HyperOS are notoriously aggressive), the whole premise needs
revisiting before we spend the other 10 weeks.

## 11. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| iOS background is too sparse to be useful | **High** | Set expectations in UI/listing; lean on charging-time `BGProcessingTask`; consider push in Ph.4 |
| OEM battery killers (Samsung/Xiaomi/Huawei) drop WorkManager jobs | **High** | Test on real devices in Ph.0; offer the exemption prompt; detect and surface missed-sample gaps |
| Cellular data complaints | Medium | Tiered probes, Wi-Fi-only defaults, projected-cost UI |
| Measuring the network changes the network | Medium | Keep T1 tiny; never run T2/T3 concurrently with itself; already handled in sweep design |
| Play/App Review rejection over background use | Medium | Declare properly, no tricks, clear reviewer notes |
| Maintaining Kotlin+Swift measurement parity | Low-Med | Keep the engine small and spec'd; shared test vectors; revisit Flutter if it drifts |

## 12. Open questions for you

1. **Is Android-only v1 acceptable?** It's where the product genuinely works,
   and it removes the biggest risk from the critical path.
2. **How much does the WebSocket streaming test matter to you?** If it's the
   heart of the product, that pushes toward Flutter (real sockets in background
   isolates). If it's fine as a foreground-only feature, Option A stands.
3. **Personal tool or store product?** A personal-use Android app can use
   battery exemptions and a permanent foreground service freely and skip all
   of §9. A store product cannot. This changes the design materially.
4. **Report destination** — local notification only, or also email/webhook?
   Email implies a backend.

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
