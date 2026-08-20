# 7even sense-check and remediation plan

Reviewed: 2026-08-20  
Revision reviewed: `92998b3`  
Remediated: 2026-08-20 (this working tree)  
Scope: Cloudflare Worker, browser/PWA, shared Kotlin engine, Android host, persistence, scheduling, and deployment workflow.

This was a read-only engineering review, not a penetration test. Findings below are based on reachable code paths, focused local reproductions, builds/tests, and a check of the live response headers. “Confirmed” means the implementation demonstrates the behavior; hardening ideas are listed separately so they are not confused with proven defects.

## Remediation status

Every ticket below has been implemented except the product decision noted under SEC-02, which needs an owner rather than code.

| Ticket | Status | Where |
|---|---|---|
| SEC-01 | **Fixed** | `src/index.js` rewritten; 19 protocol tests in `test/worker.test.mjs` |
| SEC-02 | **Contained** — the product decision is still open | Exact-host origin allowlist, `timingSafeEqual`, per-connection quotas; `Origin` documented as a cross-site check only |
| DATA-01 | **Fixed** | `TransferPlan.kt` + `AutomaticTransfers`; projection and execution read one plan |
| MON-01 | **Fixed** | Connectivity constraint removed; `DropDetector.Snapshot` persisted exactly |
| REPORT-01 | **Fixed** | Reports persisted before delivery; permission requested contextually; pending report shown in-app |
| WEB-01 | **Fixed** | Session-wide `AbortController`, serial ping loop, generation checks before mutation |
| WEB-02 | **Fixed** | Status and exact byte length required on every rung |
| WEB-03 | **Fixed** | `UPLOAD_TIMEOUT_MS` honoured, per-round ids, reconnect after a timed-out round |
| NET-01 | **Fixed** | `KtorTransport` status/length checks; `WsLiveTestClient` round ids and reconnect |
| AND-01 | **Fixed** | Per-phase deadlines enforced inside the episode; `CancellationException` rethrown throughout |
| MON-02 | **Fixed** | One `streamUrl`; light tier uses `lightDownBytes`/`lightUpBytes` |
| REPORT-02 | **Fixed** | Unobserved windows are unscored; first-install baseline recorded |
| REPORT-03 | **Fixed** | Half-open `[start, end)`; unknown coverage is explicit |
| HIST-01 | **Fixed** | Filter tuple validated, ascending charts, one CSV schema, `FileProvider` export |
| SCHED-01 | **Fixed** | One `scheduleFromConfig` entry point; charging gates heavy work only |
| REL-01 | **Fixed** | `verify` job gates ordered deploys; Wrangler replaces the archived Pages action |
| PWA-01 | **Fixed** | `waitUntil`ed cache writes, OK-only caching, `ignoreSearch` fallback |
| SEC-03 | **Fixed** | Backup exclusion rules, `.gitignore` for secrets, `local.properties.example` |
| AND-02 | **Fixed** | Lint error cleared; Android host tests added; Room schema exported |
| CLEAN-01 | **Fixed** | Stray `src/index` deleted, docs corrected, units and labels made honest |

The shared-core, browser/PWA, and defense-in-depth backlogs below are implemented too.

### Verification after remediation

| Check | Result |
|---|---|
| `node --check src/index.js`, `node --check sw.js` | Pass |
| `node scripts/check-inline-script.mjs` | Pass |
| `node --test "test/**/*.test.mjs"` | Pass: 19 tests |
| `./gradlew :shared:check` | Pass: 154 tests, 0 failures |
| `./gradlew :androidApp:assembleDebug` | Pass |
| `./gradlew :androidApp:lintDebug` | Pass: 0 errors (37 warnings, dependency freshness and icon polish) |
| `./gradlew :androidApp:testDebugUnitTest` | Pass: 6 tests |

### Still open

- **SEC-02's product decision.** The Worker is currently a public measurement service whose abuse controls are quotas, not authentication. If it should instead be restricted, that needs server-issued short-lived credentials — the APK-held key can never provide them.
- **Instrumentation tests.** The Android host tests added here are JVM unit tests. WorkManager, Room migration, and notification-permission flows still need on-device instrumentation coverage.
- **A strict CSP.** `_headers` locks down everything except `script-src`/`style-src`, which still need `'unsafe-inline'` until the inline script and styles are extracted to files.

## Executive summary

The codebase was in better shape than the highest-risk findings might suggest: the shared suite had 127 passing tests, the Android debug APK built, and the JavaScript parsed cleanly. The main problems sat at integration boundaries that those tests did not cover.

These were the release blockers, all now addressed:

1. The public Worker can be pushed into a non-progressing CPU loop with a fractional download size.
2. Worker authentication is not a security boundary: `Origin` is spoofable and the alternative signing key is recoverable from the APK.
3. Automatic mobile stream/sweep work bypasses the displayed data projection, daily sweep cap, and part of the usage accounting. A default mobile sweep every 15 minutes is roughly 342 MB/day, or 10.3 GB per 30 days, while the UI projection barely changes.
4. Android cannot observe a disconnected interval because WorkManager requires connectivity; even if invoked with no network, the persisted failure counter is reset before the next cycle.
5. On Android 13+, a report can be marked delivered when notification permission was never requested and no notification was shown.

Recommended delivery order:

| Phase | Goal | Tickets |
|---|---|---|
| 0 | Stop abuse, silent data spend, and lost outage/report data | SEC-01, SEC-02, DATA-01, MON-01, REPORT-01 |
| 1 | Make measurements and cancellation trustworthy | WEB-01, WEB-02, WEB-03, NET-01, AND-01, MON-02 |
| 2 | Repair report/history/scheduling semantics | REPORT-02, REPORT-03, HIST-01, SCHED-01 |
| 3 | Add release gates and defense in depth | REL-01, PWA-01, SEC-03, AND-02, CLEAN-01 |

## Phase 0 — release blockers

### ✅ SEC-01 — Reject malformed Worker protocol input and cap work

**Severity:** Critical  
**Area:** Worker/WebSocket  
**Evidence:** `src/index.js:148`, `src/index.js:155-164`

`down_start.bytes` accepts coercible fractional values. On the last iteration, `Uint8Array.slice(0, remaining)` truncates a remainder below one byte to a zero-length frame. `sent` then stops increasing while `sent < totalBytes` remains true. Inputs `0.5`, `1.5`, and `65536.5` reproduce the loop locally. `JSON.parse("null")` also reaches `msg.type` and throws.

Small fix:

- Parse into an unknown value and require a non-null plain object before reading `type`.
- Require `typeof bytes === "number"`, `Number.isSafeInteger(bytes)`, and `1 <= bytes <= MAX_BYTES_PER_COMMAND`.
- Close protocol violations with WebSocket code `1008`; do not try to coerce strings or fractions.
- Add cumulative per-connection byte, command, and lifetime limits. The existing maximum is per command, so one connection can request it indefinitely.
- Make uploads declare an expected size/round ID and close the connection if binary input exceeds the declared or cumulative limit; the current `up_start` path accepts an unlimited stream.
- Avoid an unbounded synchronous send loop for large responses; send bounded batches and yield or use an explicit backpressure strategy.

Acceptance checks:

- `null`, arrays, strings, `NaN`, infinities, negative/zero/fractional sizes, and values above the cap close cleanly without sending data.
- Valid edge sizes send exactly the requested number of bytes and terminate.
- Repeated valid commands hit a documented connection quota.
- A regression test proves every loop iteration makes positive progress.

### 🟡 SEC-02 — Replace the forgeable Worker authentication model

**Severity:** High  
**Area:** Worker + Android  
**Evidence:** `src/index.js:13-22`, `src/index.js:93-103`, `mobile/androidApp/build.gradle.kts:25-40`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/net/NativeAuth.kt:29-43`, `wrangler.toml:5-10`

Requests with an allowed `Origin` skip HMAC verification. A non-browser client can supply any `Origin`, including the production origin. The allowlist also accepts every `*.pages.dev` site, including attacker-owned projects. Native clients use a reusable symmetric key compiled into the APK; extracting one APK allows new valid tokens to be minted indefinitely, regardless of their short expiry.

This needs one explicit product decision:

- **Public measurement service:** remove the appearance of client authentication, then control abuse with Cloudflare rate limiting, per-IP/per-session connection and byte quotas, a strict protocol state machine, and observability.
- **Restricted service:** issue short-lived, scoped session credentials from a trusted server after an appropriate proof such as account authorization or attestation. Do not distribute the signing secret to clients.

Immediate containment while that decision is implemented:

- Treat `Origin` only as an additional browser cross-site check, never as authentication.
- Allow exact HTTPS origins only: production plus this project’s actual preview-host pattern, not all of `pages.dev`.
- Rotate the existing HMAC secret after migration and fail release builds when required security configuration is absent.
- If HMAC remains temporarily, compare decoded fixed-length MAC bytes with `crypto.subtle.timingSafeEqual` and add nonce/replay handling. This improves implementation hygiene but does not make an APK-held secret safe.

Acceptance checks:

- Spoofing the production `Origin` from a non-browser client grants no extra authority.
- An unrelated `*.pages.dev` origin is rejected.
- Decompiling the APK does not yield a credential that can authorize arbitrary future sessions.
- Load tests demonstrate enforced connection/byte/rate limits and useful rejection metrics without token logging.

### ✅ DATA-01 — Put every automatic transfer behind one real budget

**Severity:** High  
**Area:** Shared policy + Android live tests + settings  
**Evidence:** `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/work/ProbeWorker.kt:57-83`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/livetest/LiveTestRunner.kt:131-194`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/MonitorCoordinator.kt:44-49,85-86`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/ProbeConfig.kt:152-201`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/ui/SettingsScreen.kt:279-330`

`ProbeWorker` launches automatic stream/sweep work on every WorkManager wake when enabled. That path does not consult `fullSweepsPerDay`, and `DataBudget.project` ignores both automatic toggles and their configured sweep plans. The coordinator disables its legacy throughput scheduling on ordinary cycles, so the projection mostly models a path that does not run.

Sweep bytes are never added to “Used this month”; stream accounting occurs only after an episode and treats only `CELLULAR` as metered, missing metered/tethered Wi-Fi. The mobile default ladder moves 1.784 MB per direction, 3.568 MB per complete sweep. At a 15-minute interval that is about 342 MB/day and 10.3 GB/30 days before stream traffic.

Small fix:

- Introduce a shared `TransferPlan`/`BudgetReservation` used by both projection and execution. It should decide network eligibility, charging requirement, next eligible time, expected maximum bytes, and remaining daily/monthly allowance.
- Reserve allowance atomically before automatic work; reconcile it with actual attempted/partial bytes afterward. Concurrent/retried workers must not double-spend the allowance.
- Apply the existing full-sweeps-per-day rule to the actual `LiveTestRunner` path, not only the dormant HTTP tier.
- Pass the OS-provided `DeviceState.isMetered`; do not infer metering from cellular vs Wi-Fi.
- Account every direction and outcome, including partial/failed sweeps and cancelled streams. Keep projected and actual categories explicit.
- Cap custom plan rows, trials, per-run bytes, duration, daily bytes, and monthly metered bytes in the model as well as the UI.
- Show stream estimates as a range or maximum because duration-based streams have variable traffic.

Acceptance checks:

- Enabling any automatic transfer changes the pre-save projection.
- Execution of the same plan cannot exceed its stated hard maximum or configured daily count.
- Metered Wi-Fi is charged to the metered counter.
- Successful, failed, partial, cancelled, and retried sweep/stream tests all reconcile usage correctly.
- A test simulating 96 wakes/day proves the configured cap, rather than WorkManager cadence, bounds usage.

### ✅ MON-01 — Make disconnected cycles observable across WorkManager runs

**Severity:** High  
**Area:** Android scheduling + shared drop state  
**Evidence:** `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/work/ProbeWorker.kt:94-114,132-143`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/ProbeEngine.kt:55-82`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/MonitorCoordinator.kt:50-82`

The worker requires `NetworkType.CONNECTED`, so it is not run while the device is disconnected even though the engine deliberately models `NetworkType.NONE` as a failed observation. If called manually with no network, the engine emits one failure. The coordinator then persists `consecutiveFailures = 0` unless a drop already opened. With the detector threshold of two, repeated offline worker invocations never reach the threshold. The first pre-threshold failure time is also lost.

Small fix:

- Remove the WorkManager connectivity constraint from the reachability worker. Let the worker record current connectivity first, then gate only network transfers.
- Expose and persist the detector’s exact snapshot: consecutive count, start time of the current failure run, open-drop start, and closed drops. Do not reconstruct it from `isDropOpen` or `pingsPerCycle`.
- Migrate existing stored state safely and document how missing historical pre-threshold timestamps are handled.

Acceptance checks:

- Two separate offline worker runs open one drop at the first failure timestamp.
- A later success closes that same drop; process recreation between every cycle does not change the result.
- An excluded network is still skipped intentionally and does not become an outage.
- No HTTP/WebSocket transfer is attempted when the network is absent.

### ✅ REPORT-01 — Do not mark an undelivered Android notification as delivered

**Severity:** High  
**Area:** Android permission + report persistence  
**Evidence:** `mobile/androidApp/src/main/AndroidManifest.xml:15`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/ui/MainActivity.kt:71-76,108-110`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/work/ReportWorker.kt:69-87`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/data/Entities.kt:145-155`

Android 13+ notification permission is declared but never requested. `ReportWorker` advances `lastReportAt` before suppressing the notification when permission is missing. Reports are not durably stored, so the user can permanently lose the result.

Small fix:

- Request notification permission contextually when the user enables scheduled reports/monitoring; explain what will be delivered.
- Persist a generated report or pending-notification record before attempting delivery.
- Make notification delivery return a result and update `lastDeliveredAt` only after a successful post. Track “generated” and “delivered” separately.
- Surface pending/unread reports in-app so denial of notification permission does not lose data.

Acceptance checks:

- API 33+ allow and deny flows are covered by instrumentation tests.
- Denial leaves a pending report visible in-app and does not advance the delivery timestamp.
- Granting permission later can deliver or acknowledge pending reports exactly once.

## Phase 1 — measurement correctness and lifecycle

### ✅ WEB-01 — Serialize browser probes and isolate session generations

**Severity:** High  
**Area:** Browser session lifecycle  
**Evidence:** `index.html:1105-1110`, `index.html:1490-1509`, `index.html:2235-2238`, `index.html:2251-2294`, `index.html:2383-2386`

Ping requests own local abort controllers that Stop cannot reach. An old request can complete after Stop or after a new Start and mutate/archive the wrong session. Probes run from `setInterval` every three seconds while the timeout can be 30 seconds, allowing about ten overlapping probes with out-of-order drop transitions.

Small fix:

- Give each session a generation ID and shared `AbortController`.
- Pass both into every async operation and reject stale completions before any state mutation or persistence.
- Replace `setInterval` with serial self-scheduling: schedule the next ping only after the current one settles.
- Check generation immediately after stream/sweep awaits; current stream mutation happens before the late generation check.

Acceptance checks:

- Stop aborts ping, stream, download sweep, and upload sweep promptly.
- Stop followed immediately by Start cannot credit an old completion to the new session.
- At most one ping is in flight even when timeout exceeds interval.
- Fake-timer tests cover response/abort races and out-of-order completion.

### ✅ WEB-02 — Treat HTTP errors and short bodies as failed measurements

**Severity:** High  
**Area:** Browser HTTP measurement  
**Evidence:** `index.html:1105-1109`, `index.html:1529-1531`, `index.html:1563-1568`

Trace pings treat any HTTP response as success. Download sweeps buffer any status/body and treat promise resolution as a passing requested size. A small `429`, `403`, `404`, or `500` response can therefore be reported as a successful multi-megabyte transfer.

Small fix:

- Require the expected successful status and reject other statuses with a typed reason.
- For size endpoints, require `body.byteLength === requestedBytes`; record expected and actual lengths.
- Decide explicitly whether redirects are valid and ensure the final origin/endpoint is expected.

Acceptance checks:

- `429`, `500`, redirect-to-login, zero-byte, and truncated bodies are failures.
- Only an exact valid payload passes a rung.
- Logs/export retain status and actual bytes without storing response content.

### ✅ WEB-03 — Correlate upload rounds and honor their timeout/cancellation

**Severity:** Medium  
**Area:** Browser WebSocket upload sweep  
**Evidence:** `index.html:725-739,790-809,1593-1693`

`UPLOAD_TIMEOUT_MS` is declared and persisted but the upload sweep uses `SWEEP_TIMEOUT_MS` for connection and every attempt. A timed-out attempt leaves the socket open; the next attempt reuses it and accepts the first uncorrelated `up_ack`, so a delayed acknowledgement from attempt N can falsely pass attempt N+1. Stop has no handle for this socket and may wait for the timeout.

Small fix:

- Use the configured upload timeout for upload connection/rounds.
- Add a unique round ID to `up_start`, `up_end`, and `up_ack`; accept only the active ID.
- Close/reconnect after timeout or protocol failure.
- Pass the session abort signal into connection and round helpers and settle once on abort.

Acceptance checks:

- A deliberately delayed ACK cannot pass a later attempt.
- Changing upload timeout changes actual behavior.
- Stop closes the upload socket promptly and produces no later UI/history mutation.

### ✅ NET-01 — Apply the same response-integrity rules on Android

**Severity:** High  
**Area:** Android Ktor + WebSocket transport  
**Evidence:** `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/net/KtorTransport.kt:38-82`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/net/WsLiveTestClient.kt:115-116,171-172,198-199,228-229`

Android download measurement ignores the expected byte count and response status; header-only `timedGet` does not consume/close the body as a complete request measurement. WebSocket timeouts can leave a reusable connection with late frames/ACKs, allowing one round to contaminate the next.

Small fix:

- Reject non-2xx responses and distinguish exact, partial, and failed transfer results by actual byte count.
- Consume or explicitly close timed GET bodies and define whether RTT means headers or completed response.
- Close/reconnect a WebSocket after a timed-out round, or add request IDs echoed by the server and ignore unrelated frames.
- Share protocol conformance tests between browser and Android where practical.

Acceptance checks:

- Error/short-body cases match WEB-02 on Android.
- A delayed ACK from attempt N cannot pass attempt N+1.
- Connections and response bodies are closed on success, timeout, cancellation, and protocol error.

### ✅ AND-01 — Honor manual-test deadlines and preserve coroutine cancellation

**Severity:** High  
**Area:** Android workers/network/UI  
**Evidence:** `LiveTestRunner.kt:94-128` treats the configured duration as a minimum and starts sweeps afterward; broad `catch (Throwable)` blocks in `ProbeWorker.kt:86-90`, `ReportWorker.kt:72-73`, `KtorTransport.kt:55-56,75-82,102-105`, `WsLiveTestClient.kt:115-116,171-172,198-199,228-229`, and `DashboardViewModel.kt:198-208` swallow cancellation. `DashboardViewModel.kt:170-193` lets ping callbacks overwrite the visible stream step, while `MainActivity.kt:237-253` reads persisted historical throughput for a current manual test.

A manual 10-second test can therefore run a 15-second download, a 15-second upload, and both sweeps after its countdown reaches zero. Pings keep repainting the chart and resetting the visible step. A slow or cancelled mobile run may then display a previous Wi-Fi throughput value as its “last” result. These catches also include `CancellationException`, so Stop/cancel can be converted into a failed measurement or WorkManager retry.

Small fix:

- Rethrow `CancellationException` before mapping other failures, or catch only expected transport/protocol exceptions.
- Treat a finite manual duration as a hard deadline covering ping, stream, and sweep work; cancel all child work at that deadline and never begin a later phase afterward.
- Scope UI callbacks to a manual-run generation so an old/cancelled Wi-Fi run cannot mutate a newer cellular run. Do not let ping callbacks overwrite an active stream/sweep step.
- While a manual run/result is displayed, render only that run’s live data; use an honest dash for an unmeasured direction instead of falling back to historical throughput from another network.
- Scale sub-Mbps charts from their observed range and label ticks in Kbit/s. The Worker should send small enough download frames for a slow link to emit progress before a short test ends.
- Add `ensureActive()` in long transfer loops and close resources in `finally`.
- Re-check that monitoring is still enabled at worker start and immediately before heavy work.

Acceptance checks:

- A 10-second manual cellular test has no ping, stream, or sweep callbacks after 10 seconds and never advances to step 4/5 afterward.
- A slow (sub-Mbps) link shows visible Kbit/s live samples as soon as frames arrive; if no direction is measured before the deadline, its result remains empty rather than showing an old Wi-Fi value.
- Cancelling each worker or live test finishes promptly, closes sockets/clients, writes no bogus failure, and schedules no retry solely because of cancellation.

### ✅ MON-02 — Unify configuration fields with the paths that actually execute

**Severity:** Medium  
**Area:** Shared config + Android settings/runner  
**Evidence:** `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/ProbeConfig.kt:38-60`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/ProbeEngine.kt:139-149`, `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/livetest/LiveTestRunner.kt:54-69`

There are two WebSocket endpoint fields. Settings edits/persists `streamUrl`, while `LiveTestRunner` uses `wsUrl`, so the visible custom endpoint is ignored. The light data projection uses `lightDownBytes`/`lightUpBytes`, while `ProbeEngine` uses `measurementSizes(network)` and the same size for upload and download. UI settings can therefore alter displayed cost without altering traffic.

Small fix:

- Consolidate to one endpoint with a stored-config migration.
- Define one light transfer plan with separate download/upload sizes and have both execution and projection consume it.
- Remove or formally deprecate dead fields; avoid silent same-default duplicates.

Acceptance checks:

- An integration test proves the configured URL reaches the WebSocket client.
- Changing light down/up values changes both planned and actual bytes identically.
- Existing installations migrate without resetting unrelated settings.

## Phase 2 — reporting, history, and scheduling

### ✅ REPORT-02 — Never report perfect stability without observations

**Severity:** High  
**Area:** Shared reports + browser sessions  
**Evidence:** `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/report/Report.kt:127-133`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/core/Stats.kt:88-108`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/probe/MonitorCoordinator.kt:97-105`, `index.html:1401-1405,2091-2099,2348-2369`

Shared reporting produces 100% uptime/no drops for an empty window. First-run report scheduling substitutes epoch zero for no prior delivery, so the first worker wake is immediately due and can notify an empty “100% uptime” report. The browser similarly scores null jitter and zero loss as perfect; a timed session reopened after its deadline can archive a full-duration perfect session with no observations.

Small fix:

- Model stability as unavailable/insufficient when there are no samples or coverage is below a documented threshold.
- Establish first-install delivery baseline semantics instead of using epoch zero.
- Track active/observed duration and last observation; closed/suspended time is unknown, not uptime.
- Keep “no failures observed” distinct from “100% available.”

Acceptance checks:

- Empty and low-coverage windows show “insufficient data,” never 100.
- First install does not produce an immediate historical report.
- Browser reopen after an unobserved gap does not turn that gap into successful monitoring.

### ✅ REPORT-03 — Use unambiguous half-open windows and schedule-aware coverage

**Severity:** Medium  
**Area:** Shared reporting/statistics  
**Evidence:** `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/report/Report.kt:65`, `mobile/shared/src/commonMain/kotlin/de/sevenapp/monitor/report/ReportSchedule.kt`, corresponding Room range queries

Inclusive `start..end` filtering can count boundary samples in two adjacent reports. For cadences longer than the report window, expected-sample integer truncation reaches zero and the coverage helper treats that as full coverage. Similar integer-day projection behavior becomes misleading for intervals longer than one day.

Small fix:

- Standardize every in-memory and SQL range on `[start, end)`.
- Make expected coverage schedule-aware and represent “not expected/unknown” explicitly rather than converting zero expected samples to 100%.
- Project a whole calendar/30-day interval directly instead of multiplying a truncated daily count.

Acceptance checks:

- A sample exactly at midnight appears in one report only.
- Weekly cadence in a daily window is unknown/not scheduled, never full coverage.
- DST and timezone boundary tests cover daily, weekly, and monthly windows.

### ✅ HIST-01 — Correct filtering, ordering, and export structure

**Severity:** Medium  
**Area:** Android history  
**Evidence:** `mobile/androidApp/src/main/kotlin/de/sevenapp/monitor/android/ui/HistoryViewModel.kt`, history screen state collection

Confirmed inconsistencies:

- Switching from a named Wi-Fi filter to mobile retains the SSID filter and can produce an empty view.
- Drops/global stability are included in filtered views even though drops have no network attribution.
- Samples are reversed before charting, so time order is newest-to-oldest.
- Drop CSV rows place `endedAt` under the download-Mbps column, so row types do not share a truthful schema.
- Sending the entire 90-day CSV in `Intent.EXTRA_TEXT` risks Android’s Binder transaction limit.
- Screens refresh mainly at initialization rather than from a lifecycle-aware reactive stream.

Small fix:

- Make filter state a validated tuple and clear incompatible SSIDs automatically.
- Either attribute drops at collection time or label aggregate stability/drops as unfiltered.
- Sort chart inputs ascending by timestamp.
- Define one documented CSV schema or separate typed sections/files.
- Export through a temporary file plus `FileProvider`/Storage Access Framework.
- Expose Room flows and collect them lifecycle-aware.

Acceptance checks:

- Filter transition tests cover Wi-Fi/SSID/mobile/all.
- Chart x values are monotonic ascending.
- Every CSV row matches headers and a large 90-day export shares successfully.

### ✅ SCHED-01 — Make schedule updates and charging semantics match the UI

**Severity:** Medium  
**Area:** Android WorkManager  
**Evidence:** `ProbeWorker.kt:57-83,132-153`, `DashboardViewModel.kt`, `BootReceiver.kt`, settings copy around `SettingsScreen.kt:135-157`

`automaticRequiresCharging` currently returns before even the cheap coordinator ping, despite UI/product language separating reachability from heavy work. Some scheduling calls omit stored hour/day. `ExistingPeriodicWorkPolicy.UPDATE` preserves enqueue history, so changing an anchor may not realign it; fixed intervals also drift across DST. WorkManager already persists across reboot, making the boot reschedule path redundant and another source of config divergence.

Small fix:

- Apply charging checks only to the heavy operation they describe.
- Route every schedule call through one function carrying interval, hour, day, and timezone.
- On anchor change, explicitly re-enqueue or use self-scheduling one-time work if wall-clock delivery matters.
- Remove redundant boot scheduling unless a tested platform case requires it.
- Separate the manual preferred network from automatic network policy; the current manual choice can force later unattended heavy tests onto cellular.

Acceptance checks:

- A not-charging wake still records reachability but skips configured heavy work.
- Editing hour/day predictably changes the next run.
- Restart/reboot and DST tests preserve documented semantics.
- A manual mobile test does not silently reconfigure automatic tests.

## Phase 3 — release engineering and hardening

### ✅ REL-01 — Put validation in front of ordered, reproducible deploys

**Severity:** Medium  
**Area:** GitHub Actions  
**Evidence:** `.github/workflows/deploy.yml:3-47`

Worker and Pages deploy directly from parallel jobs with no test/lint gate or workflow concurrency. Rapid pushes can finish out of order, and Pages can publish while a coupled Worker deployment fails. Pages uses the archived `cloudflare/pages-action@v1`.

Small fix:

- Add a verification job: JS syntax/protocol tests, `:shared:check`, and Android assemble/lint/tests on an Android-SDK runner.
- Make deployments depend on verification; deploy the Worker before a protocol-dependent UI, or maintain explicit backward-compatible protocol versions.
- Add a production concurrency group with stale runs cancelled.
- Replace Pages Action with `cloudflare/wrangler-action@v3` and `wrangler pages deploy`.
- Pin third-party actions to reviewed commit SHAs and pin the Wrangler version; add automated dependency-update PRs.

Acceptance checks:

- A failing test/lint blocks both deployments.
- Two rapid pushes cannot publish the older revision last.
- A protocol migration has an explicit compatibility/deploy-order test.

### ✅ PWA-01 — Keep a known-good offline shell

**Severity:** Medium  
**Area:** Service worker  
**Evidence:** `sw.js:46-53`

The network-first cache update is a floating promise not kept alive with the fetch event. It caches non-OK responses, so a transient error can replace the good shell. The navigation fallback uses the exact request, which can miss precached `/index.html` for URLs with query strings; if no match exists, the handler may resolve without a valid response.

Small fix:

- Await or attach cache writes to `event.waitUntil`.
- Cache only successful same-origin HTML responses.
- Fall back to canonical `/index.html` (or a deliberate `ignoreSearch` lookup), then to an explicit offline response.
- Version or content-hash cache entries when shell/manifest/icons change.

Acceptance checks:

- A `500` never replaces a good cached shell.
- `/?utm_source=...` works offline after one successful visit.
- An empty/evicted cache returns a valid offline response.

### ✅ SEC-03 — Define Android backup/privacy handling and secret hygiene

**Severity:** Medium / hardening  
**Area:** Android data + repository configuration  
**Evidence:** `mobile/androidApp/src/main/AndroidManifest.xml:28`, `.gitignore:1`, `wrangler.toml:5-10`

Android backup is enabled without data-extraction/full-backup rules even though Room contains connection history and SSIDs and the local entitlement affects access. The repository instructions tell developers to place a Worker secret in `mobile/local.properties`, but `local.properties`, `.env`, and `.dev.vars` are not ignored.

Small fix:

- Decide the intended backup policy. Either disable backup for raw monitoring data/entitlement state or add `dataExtractionRules`/`fullBackupContent` exclusions and reconcile restored entitlement with the authoritative billing source.
- Add `**/local.properties`, `.dev.vars*`, and `.env*` to `.gitignore`, with explicit `*.example` allow rules.
- Add secret scanning in pre-commit/CI and rotate any credential that is ever committed.
- Clarify SSID collection in permission/disclosure copy; workers currently read/persist it outside a History-only flow.

Acceptance checks:

- An Android backup/restore test proves excluded data and entitlement behavior.
- Secret fixture tests are detected while example files remain committable.
- No production credential is required in a distributable client after SEC-02.

### ✅ AND-02 — Clear the Android lint error and add host-level tests

**Severity:** Medium  
**Area:** Android manifest/test coverage  
**Evidence:** `mobile/androidApp/src/main/AndroidManifest.xml:10`, Android test task output

`lintDebug` fails because fine location is requested without coarse location. The Android module currently has no unit-test sources, leaving WorkManager, permission, Room, cancellation, and notification integration untested.

Small fix:

- Declare/request coarse and fine location together and support approximate permission behavior. Prefer modern `NetworkCapabilities.transportInfo` over deprecated Wi-Fi connection info where supported.
- Add Android unit/instrumentation tests for the Phase 0 and Phase 1 acceptance cases.
- Commit Room schema snapshots because `exportSchema = true`; test migrations.

Acceptance checks:

- `:androidApp:lintDebug`, assemble, unit tests, and selected instrumentation tests pass in CI.
- Denying precise location does not crash or silently corrupt network labeling.

### ✅ CLEAN-01 — Remove misleading/dead artifacts and update docs

**Severity:** Low  
**Area:** Repository/documentation/UI semantics

- Delete tracked, unreferenced `src/index` (its content is only `bbj`).
- Update `mobile/README.md`: it still says 119 shared tests and that Android is excluded/uncompiled; the review ran 127 tests and built the APK.
- Choose one throughput meaning. Browser cards say “Peak sustained,” render a running average, export the last episode, and do not reset `lastDownMbps`/`lastUpMbps` between sessions (`index.html:516,521,1940-1950,2190-2213,2427-2428`). Name/export average, peak, and last explicitly if all are needed.
- `allRtts` is documented as whole-session data but capped at 2,000 samples; either describe it as a rolling window or aggregate the full session safely.
- Rename “this month” if it remains a rolling 30-day period rather than a calendar month.
- Restore pinch zoom by removing `maximum-scale=1.0, user-scalable=no`; convert clickable `div`/`span` controls to keyboard-operable controls.

## Additional shared-core correctness backlog — ✅ done

These were confirmed as follow-ups and have been implemented:

- **Sweep cutoff overclaim:** `SweepResult.ok` means every trial passed, so a rung with 2/3 successes is treated as wholly failed and can create a “clean cutoff.” Model rungs as all-pass/mixed/all-fail and claim a cutoff only for a sustained all-fail boundary (`Sweep.kt:176-213`).
- **Soft cancellation creates fake failures:** `return@repeat` skips one iteration but still emits a result declaring all configured trials. Break the rung/test and record attempted count/cancelled state (`Sweep.kt:124-163`).
- **Partial throughput flag:** failed directions do not always set `partial`; mark partial whenever an expected direction fails or is incomplete (`ProbeEngine.kt:139-168`).
- **Atomic coordinator writes:** cycle index, samples, detector state, sweep counts, usage, and prune are separate writes. Add a store transaction and idempotent cycle ID so process death/retry cannot split state or duplicate a cycle (`MonitorCoordinator.kt:60-88`).
- **Monotonic durations:** live test durations use epoch time and can change when the wall clock is adjusted. Use a monotonic elapsed clock for durations, epoch time only for persisted timestamps.
- **Numeric validation:** reject non-finite/negative RTT and Mbps values; guard entitlement-expiry additions against overflow.
- **Drop aggregation:** normalize or merge overlapping drop intervals before calculating downtime.

## Additional browser/PWA correctness backlog — ✅ done

- **Persisted-state schema:** `loadSession()` accepts any valid JSON and restoration assigns fields directly. A corrupt/older value such as `dropEvents: {}` later fails at array operations and can break every startup until storage is cleared (`index.html:1029-1041,2304-2323`). Add a schema version, validate/sanitize each field, migrate known versions, and discard invalid snapshots/history entries.
- **Archive quota visibility:** localStorage write failures are intentionally swallowed. Preserve live operation, but show that older history was not saved and provide a recovery/export path instead of silently implying durable retention (`index.html:1016-1026`).

## Defense-in-depth backlog — ✅ done

These are worthwhile production controls; the review did not treat their absence alone as an exploit. All three are now in place — sampled observability in `wrangler.toml`, a staged CSP and the other headers in `_headers`, and the dependency work is the one part still scheduled rather than done:

- Enable sampled Worker observability and structured rejection/error metrics in `wrangler.toml`; never log credentials. Alert on protocol violations, CPU-limit events, byte-quota rejections, and unusual connection volume.
- Add Pages security headers via `_headers`: a staged Content Security Policy, HSTS, `frame-ancestors` (or equivalent frame policy), Permissions Policy, and the existing MIME/referrer controls. The current large inline script/style must be extracted or covered with maintained hashes/nonces before a strict CSP can land.
- Schedule dependency upgrades and a complete lockfile/transitive software-composition scan. The focused OSV query below covered direct declared Maven dependencies only.

## Verification performed at review time (pre-remediation)

| Check | Result |
|---|---|
| `node --check src/index.js` | Pass |
| Extracted inline browser script syntax | Pass |
| `node --check sw.js` | Pass |
| `./gradlew :shared:check` | Pass: 127 tests, 0 failures/errors/skips |
| `./gradlew :androidApp:assembleDebug` | Pass |
| `./gradlew :androidApp:lintDebug` | Fail: 1 manifest error, 37 warnings |
| `./gradlew :androidApp:testDebugUnitTest` | Success with `NO-SOURCE` |
| Focused Worker fractional-size reproduction | Reproduced non-progress for `0.5`, `1.5`, `65536.5` |
| Direct declared Maven dependency OSV query | No known advisories returned; not a transitive/full supply-chain audit |
| Secret-pattern scan of tracked files | No raw committed secret found; only configuration references |
| Live Pages headers | No CSP, HSTS, frame-ancestor/X-Frame-Options, or Permissions-Policy observed |

The 37 lint warnings are mostly dependency freshness, obsolete-min-SDK branches, icon polish, and a likely false-positive static-context warning. Treat upgrades as scheduled maintenance; the review did not identify a known vulnerability in the directly queried versions.

## Suggested PR slices

Each slice below is intentionally small enough for one owner and has its acceptance criteria above:

1. Worker input schema, progress invariant, quotas, and protocol tests (SEC-01).
2. Auth/public-service decision plus edge controls and token migration (SEC-02).
3. Shared transfer plan/reservation, execution gates, accounting, and projection tests (DATA-01).
4. Offline-capable WorkManager run plus exact detector snapshot migration/tests (MON-01).
5. Durable report generation/delivery state and Android permission flow (REPORT-01).
6. Browser generation cancellation, serialized pings, and stream mutation ordering (WEB-01).
7. Browser + Android response integrity and WebSocket round correlation (WEB-02, WEB-03, NET-01).
8. Cancellation audit and resource lifecycle tests (AND-01).
9. Config consolidation and migration (MON-02).
10. Empty/coverage/window report semantics (REPORT-02, REPORT-03).
11. History/export and scheduling cleanup (HIST-01, SCHED-01).
12. CI/deploy gate, service-worker cache, Android lint/tests, and repository hardening (Phase 3).

## Definition of done for the remediation effort

Status against each line:

- ✅ All Phase 0 acceptance checks are automated and green.
- ✅ The displayed maximum metered usage and the hard runtime budget derive from the same plan.
- ✅ Offline intervals survive process death and appear in reports with honest coverage.
- ✅ Stop/cancellation cannot mutate a later browser/mobile session.
- ✅ Only valid HTTP/WebSocket responses count as successful measurements.
- ✅ Empty/unobserved time is shown as unknown, never perfect uptime.
- ✅ Android lint and host integration tests run before production deployment.
- ✅ Worker abuse controls and sampled observability are live before broad distribution (they ship with this change; "live" means after the next deploy).

## Reference notes

- Cloudflare’s current Pages CI guidance uses Wrangler Action: <https://developers.cloudflare.com/pages/how-to/use-direct-upload-with-continuous-integration/>
- The old Pages Action is archived/deprecated: <https://github.com/cloudflare/pages-action>
- Cloudflare Worker production practices: <https://developers.cloudflare.com/workers/best-practices/workers-best-practices/>
- Android notification permission: <https://developer.android.com/develop/ui/compose/notifications/notification-permission>
- Android hardcoded-secret risk: <https://developer.android.com/privacy-and-security/risks/hardcoded-cryptographic-secrets>
- Android backup controls: <https://developer.android.com/identity/data/autobackup>
