# Publishing 7even to Google Play

Package: `de.sevenapp.monitor` · versionCode 1 · versionName 0.1.0

## 1. Signing key (one-time, do this outside the repo)

```bash
keytool -genkeypair -v \
  -keystore ~/keystores/7even-release.jks \
  -alias 7even -keyalg RSA -keysize 2048 -validity 10000
```

- Set a keystore password + key password when prompted → save both in your password manager
- Back up `7even-release.jks` to a second location immediately (encrypted cloud, second drive)
- **Losing this file = you can never update the app again**, no recovery

## 2. Add signing config

- Add to `~/.gradle/gradle.properties` (NOT in repo):
  ```
  SEVEN_KEYSTORE_PATH=/Users/nicky.reinert/keystores/7even-release.jks
  SEVEN_KEYSTORE_PASSWORD=...
  SEVEN_KEY_ALIAS=7even
  SEVEN_KEY_PASSWORD=...
  ```
- In `androidApp/build.gradle.kts`, inside `android { }`, above `buildTypes`:
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file(findProperty("SEVEN_KEYSTORE_PATH") as String)
          storePassword = findProperty("SEVEN_KEYSTORE_PASSWORD") as String
          keyAlias = findProperty("SEVEN_KEY_ALIAS") as String
          keyPassword = findProperty("SEVEN_KEY_PASSWORD") as String
      }
  }
  ```
- In `buildTypes { release { ... } }` add: `signingConfig = signingConfigs.getByName("release")`
- Bump `versionCode` +1 every release (Play rejects duplicates)

## 3. Build

```bash
cd mobile
./gradlew :androidApp:bundleRelease
```

- Output: `androidApp/build/outputs/bundle/release/androidApp-release.aab`

## 4. Play Console account (one-time)

- Go to → `play.google.com/console/signup`
- Pay $25 one-time fee, verify ID
- New personal accounts get a ~14-day identity/account review — start this early
- Click **Create app** → name "7even" → Free → App (not Game) → package name locks to `de.sevenapp.monitor` on first upload

## 5. Store listing — fill in on the app's **Store presence → Main store listing** page

| Field | What to use |
|---|---|
| App icon | upload `icons/play-store-icon-512.png` |
| Feature graphic (1024×500) | **not made yet** — needs its own banner design |
| Screenshots | the 3 screenshots already captured (Monitor/History/Settings) |
| Short description (≤80 char) | "Continuous connection monitoring: latency, jitter, loss, throughput." |
| Full description | expand from `manifest.json`'s description |

## 6. App content — **Policy → App content** tab, fill out every section it flags

- **Privacy policy URL**: required (app uses location) — point to a page on the 7even website
- **Data safety form**: declare location (SSID tagging, not shared), network state, notifications, foreground service, boot receiver
- **Content rating questionnaire**: no UGC/social/ads → should land on "Everyone"
- **Target audience & ads**: not for children, no ads

## 7. Testing → Production

- Upload the `.aab` under **Testing → Internal testing** first, click **Save → Review release → Start rollout**
- Then **Testing → Closed testing**: add 12+ testers (emails or Google Group), share opt-in URL
- Run for 14 continuous days — required before **Production** track unlocks for new accounts
- Then **Production → Create new release**, upload same `.aab`, staged rollout (20% → 50% → 100%)

## 8. Play App Signing

- On first upload, accept **Play App Signing** opt-in when prompted (default, recommended)
- Your keystore becomes the "upload key" (auth only); Google holds the real signing key
