# Architecture

How the app works, file by file. Last checked against the code: 2026-11-11 (version 0.4.17).

## 1. The pipeline

```
Back camera ──NV21 frames (640x480)──▶ CameraService, "camera" thread
  │ every frame: buffer handed straight back (2 reused byte arrays, no garbage)
  │ every 60 s: frame → /sdcard/cctv/test.jpg (to check the view)
  │ ~2 frames/s: core/MotionDetector (Y plane → 80x60 grid, brightness-normalised diff)
  │     changed share ≥ motionThreshold in motionFrames checks in a row = MOTION
  │        ├─▶ clips/<time>.jpg ─▶ Bot: alert photo (alerts ON, cooldown)
  │        └─▶ MediaRecorder: clips/<time>.mp4, 20 s, hardware H.264, no audio
  │              └─▶ camera closed + reopened (see INCIDENTS: reconnect hang)
  │                    └─▶ Bot: the clip as a video (videos ON, same cooldown)
  ├─ Storage: size cap (oldest first), min free space, broken clips deleted at start
  ├─ every 60 s: battery + CPU temperature → core/BatteryMonitor + core/HeatMonitor
  │     → Telegram warnings; EMERGENCY pauses the camera until it cools
  └─ every 10 min: "status:" log line (RAM, motion stats, storage, battery)

Bot: "tg-poll" long-polls Telegram (50 s requests) → commands
     "tg-text" sends messages, "tg-media" sends photos/videos (separate lanes)
Telegram API ◀── outgoing HTTPS only. The app opens no ports.
```

## 2. Files

### App (`android/app/src/main/java/com/nandan/cctv/`)

| File | What it does |
|---|---|
| `CctvApp.kt` | Application class: logs "process started, version …", installs the crash handler (writes `CRASH` to the log). |
| `MainActivity.kt` | The only screen. Opening it starts the service with `ACTION_OPEN`, which also reloads settings. |
| `CameraService.kt` | Foreground service, the heart of the app: camera, motion, recording, heat pause, battery/CPU checks, watchdog, 1-min alarm, status text, Telegram command handlers (`Bot.Control`). ~870 lines; `/simplify` planned before Phase 10. |
| `Bot.kt` | Telegram bot: polling thread, two send lanes with retry-until-delivered, outage summary, late-command rules, command routing, `/help` text. |
| `TelegramApi.kt` | Plain `HttpURLConnection` client: `getMe`, `getUpdates`, `sendMessage`, `sendPhoto`, `sendVideo` (streamed multipart). Error text has the token removed (`safe()`). |
| `Config.kt` | Reads settings JSON; imports `/sdcard/cctv/config.json` into private storage and deletes the public copy. |
| `BatteryInfo.kt` | Reads Android's battery broadcast (%, status, plug, °C, mV, health). |
| `Storage.kt` | Clip folder: size cap, free space, deletes broken `.mp4` and `.tmp` files at startup. |
| `Photo.kt` | Rotates a camera JPEG upright and stamps the time on it (for Telegram). |
| `FileLog.kt` | Log file `/sdcard/cctv/log.txt`, rotated to `log.1.txt` at 1 MB; `tail()` for `/log`. |
| `BootReceiver.kt` | Starts the service after boot (on Wi-Fi/network/charger/unlock events) and after an update. |
| `Termux.kt` | Asks Termux (RUN_COMMAND) to run `~/.termux/boot/start-sshd`. |

### `core` package (`…/cctv/core/`): pure Kotlin, unit-tested

**Rule:** no `android.*` imports. `scripts/jvmtest.sh` compiles it without the Android SDK, so an Android import breaks the build.

| File | What it does | Tests |
|---|---|---|
| `MotionDetector.kt` | 80x60 grid diff with brightness normalisation; `lastMean` for darkness detection (Task 15). | `MotionDetectorTest` (5) |
| `HeatMonitor.kt` | One sensor's temperature levels with hysteresis → Notify / PauseForHeat / ResumeAfterHeat; `startPausedIfHot` at service start. | `HeatMonitorTest` (7) |
| `BatteryMonitor.kt` | Battery warnings: temperature (via a HeatMonitor), low %, low voltage, charging stopped/resumed, health change. | `BatteryMonitorTest` (10) |
| `OutageTracker.kt` | Start/end of Telegram outages (≥ 60 s), motions while offline, time offline so far. | `OutageTrackerTest` (4) |
| `NetDiag.kt` | Phone IP from Android's little-endian int; likely outage cause (Wi-Fi down / login page / no internet / Telegram only). | `NetDiagTest` (6) |
| `LateCommands.kt` | Which late commands still run (settings, read-only) and which are skipped; what counts as a command. | `LateCommandsTest` (6) |
| `Auth.kt` | Owner + approved members, private chats only; JSON; corrupt JSON → owner only (wired in Task 8). | `AuthTest` (7) |
| `Commands.kt` | Parse `/name@bot arg rest`; members may use only `/start /help /snapshot /clip /last /status`. | `CommandsTest` (5) |
| `ClipSlot.kt` | One `/clip` request at a time; a second asker is told to wait. | `ClipSlotTest` (2) |
| `ConfigImport.kt` | `/sdcard` config imported only on first setup or the owner's `/reload`. | `ConfigImportTest` (3) |
| `Radios.kt` | The `/status` radio line (`Radios: Bluetooth off`). | `RadiosTest` (1) |
| `RateLimiter.kt` | At most N events per sliding window per user id; forgets expired ids beyond 256. | `RateLimiterTest` (4) |
| `Routing.kt` | Message → ignore (not a private chat) / stranger notice / unknown command / "owner only" / run; member rate limit (owner exempt); member `/status` without the Wi-Fi line; stranger notices (once a day each, ≤ 5 an hour). | `RoutingTest` (9) |
| `SendPolicy.kt` | After a failed send: retry (network, 429, 5xx) or drop (other 4xx, missing file). | `SendPolicyTest` (5) |
| `TestTemp.kt` | `/testtemp` argument parsing within each monitor's valid range. | `TestTempTest` (4) |
| `StorageMath.kt` | MB/day from the last 3 complete days; days until the size cap starts deleting, or at the cap the days of history it holds (`storageOutlook`). | `StorageMathTest` (6) |
| `DailyStats.kt` | Daily-summary counters (motions, clips, alerts, restarts, offline min, max CPU °C), JSON in prefs, when the summary is due. | `DailyStatsTest` (4) |

### Other files

| File | What it does |
|---|---|
| `android/app/build.gradle.kts` | App build: minSdk 21, targetSdk 22, armeabi-v7a, signing from env vars, test-only deps (JUnit 4.13.2, org.json 20231013). |
| `android/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Pinned AGP 8.7.3, Kotlin 2.1.0; no AndroidX. |
| `AndroidManifest.xml` | Permissions, service, receiver actions (see section 4). |
| `.github/workflows/build.yml` | CI: JDK 17, Gradle 8.10.2, decode keystore secret, `testReleaseUnitTest assembleRelease`, upload `cctv-apk`. |
| `scripts/jvmtest.sh` | Local JVM tests for `core` with `kotlinc` (jars from Maven Central, SHA-1 checked). |
| `scripts/deploy.sh` | Download newest APK, copy to the phone, wait for unlock, open installer. |
| `scripts/set-telegram.sh` | First setup only: puts the bot token + your user ID on the phone without printing the token. |
| `phone/termux/start-sshd`, `stop-sshd` | Copied to `~/.termux/boot/` on the phone; run by the app through Termux RUN_COMMAND. `stop-sshd` kills the listener only (open sessions survive). |

## 3. Threads

| Thread | Runs | Notes |
|---|---|---|
| main | UI, service callbacks, the 1-px overlay fallback | Kept light. |
| camera (`HandlerThread`) | All camera + MediaRecorder calls and callbacks, motion, watchdog (10 s), battery/CPU check (60 s), status log (10 min), command work | One thread = no locking for camera state. |
| tg-poll | `getUpdates` loop, command parsing | Calls `Bot.Control`; work is posted to the camera thread. |
| tg-text | Sends messages, one at a time | Queue max 20, oldest dropped (logged). Retries until sent. |
| tg-media | Sends photos and videos, one at a time | Same rules. A stuck upload can't delay text. |

## 4. Lifecycle

| Event | What happens |
|---|---|
| App opened by hand | `MainActivity` → service with `ACTION_OPEN`: first start, or settings reload. A `/sdcard` config is imported only if the app has no token yet; otherwise it is deleted and the owner warned. |
| Phone boots | `BOOT_COMPLETED` never arrives on this phone. `WIFI_STATE_CHANGED` (or network/charger/unlock/storage events) wakes `BootReceiver` within seconds; if the service isn't running it starts it. Reason "phone rebooted (first signal: …)". |
| App updated | `MY_PACKAGE_REPLACED` → service starts by itself ("app updated"). |
| Service/process dies | Android's START_STICKY restart doesn't happen on this phone. A **1-minute AlarmManager** check starts the service if it isn't running. |
| First start after each boot | Only with `sshdAtBoot: true`: asks Termux to run `start-sshd`. By default SSH stays closed after a boot until `/sshd on`. |
| Every minute | Past the `/sshd on` deadline (prefs `sshdUntil`, survives restarts) → `stop-sshd` (log `termux: asked to run stop-sshd (auto-off)`). |
| Every start | "🟢 CCTV back online (vX) Why: … Wi-Fi: <network>, IP <address> …" sent, retried until Telegram is reachable; the text is built when it goes out, so the IP is current. |
| Too hot | A HeatMonitor reaches EMERGENCY → recording stopped, camera closed, watchdog won't restart it; resumes when every sensor is below its resume point. At service start the CPU is read once: at or above its resume line the camera starts paused (no message). |
| Clip finished | Camera released and reopened (~0.3 s), 1.5 s warm-up, detection resumes. |

## 5. Storage on the phone (`/sdcard/cctv/`)

| Path | What |
|---|---|
| `log.txt`, `log.1.txt` | App log, 1 MB each. |
| `test.jpg` | Latest frame, every 60 s. |
| `clips/YYYY-MM-DD_HH-MM-SS.jpg` | Photo at the moment motion was seen. |
| `clips/YYYY-MM-DD_HH-MM-SS.mp4` | The clip (~2.3 MB per 20 s). |
| `config.json` | Only briefly: dropped there, then moved to app-private storage. |

App-private (`/data/data/com.nandan.cctv/`): `files/config.json` (includes the token), `files/users.json` (members added with `/allow`; written via a temp file), shared prefs `state` (record/alerts/videos switches, `sshdBootMin`, `sshdUntil`, `daily` = DailyStats JSON, `dailySentDay`).

## 6. Config keys (`config.json`, all optional)

| Key | Default | Meaning |
|---|---|---|
| `telegramBotToken` | "" | Bot token (set with `scripts/set-telegram.sh`). Never logged. |
| `telegramUserId` | 0 | The only Telegram user who gets answers (owner). |
| `motionPixelDiff` | 25 | Brightness change (0–255) for a grid cell to count as changed. |
| `motionThreshold` | 0.02 | Share of changed cells that counts as motion (2%). |
| `motionFrames` | 2 | Checks in a row that must show motion. |
| `analyzeFps` | 2 | Frames analysed per second (1–5). |
| `alertCooldownSeconds` | 120 | Minimum time between alert photos (and videos). |
| `clipSeconds` | 20 | Clip length (5–60). |
| `storageCapMB` | 1200 | Clip folder cap; oldest deleted first. |
| `minFreeMB` | 300 | Don't record below this much free space. |
| `tempWarnC` / `tempCriticalC` / `tempEmergencyC` / `tempResumeC` | 40 / 45 / 50 / 42 | Battery temperature levels (°C). |
| `cpuWarnC` / `cpuCriticalC` / `cpuEmergencyC` / `cpuResumeC` | 52 / 55 / 60 / 55 | Processor (`sensor_arm`) levels (°C), measured 2026-11-11. |
| `dailySummaryHour` | 9 | Hour (0–23, phone time) for the daily summary. |
| `sshdAtBoot` | false | Start sshd at every boot (development at home). |

How the levels move (HeatMonitor, hysteresis 2 °C): a level is entered as soon as its threshold is reached; it is left only below an exit point. CPU: WARNING exits below 50, CRITICAL below 53, EMERGENCY below 55 (= resume; the camera restarts and the state drops to CRITICAL if the reading is 53–54.9, without a new message). Battery: exits below 38 / 43 / 42. Messages: on every rise, "❄️ … camera back on" when leaving EMERGENCY, "✅ … normal" on reaching NORMAL.

Change settings: write `/sdcard/cctv/config.json` over SSH, then open the app (`am start -n com.nandan.cctv/.MainActivity`) or send `/reload`. The import **replaces** the whole private config, so include the Telegram keys too.

## 7. Telegram commands

The owner (`telegramUserId`) and members added with `/allow` get answers, in private chats only. Strangers get no reply; the owner gets "👤 Unknown user … /allow N" once a day per stranger. 10 messages a minute per member (the owner is not limited, so a backlog after an outage all runs). Alerts are one queue item each; recipients are read when it is sent. Motion alert photos and videos go to everyone; warnings, back-online, outage and daily summaries go to the owner only.

| Command | What it does | Who | Runs if it arrives > 2 min late? |
|---|---|---|---|
| `/start`, `/help` | Command list | everyone | yes |
| `/snapshot` | Photo now | member | no (skipped, you're told) |
| `/clip N` | Record N s (1–30) and send it; "Busy …" if another `/clip` is waiting | member | no |
| `/last` | Newest saved clip | member | yes |
| `/status` | Uptime, battery, temps, storage, switches, RAM, Wi-Fi (network, IP, signal; owner only), radios (Bluetooth), camera | member | yes |
| `/record on\|off` | Record clips on motion | owner | yes |
| `/alerts on\|off` | Photo alerts on motion | owner | yes |
| `/videos on\|off` | Also send the motion clip | owner | yes |
| `/log` | Last 30 log lines | owner | yes |
| `/reload` | Re-read settings; the only way to import a new `/sdcard` config once a token is set | owner | yes |
| `/summary` | Counts since the last daily summary + storage line (no reset) | owner | yes |
| `/allow ID` | Add a member (they get "✅ You can now use this camera") | owner | yes |
| `/revoke ID` | Remove a member | owner | yes |
| `/users` | List owner and members | owner | yes |
| `/sshd on [min]`, `/sshd off` | SSH on for N minutes (default 30, 5–120; plain `/sshd` = on 30), then off by itself; off now | owner | no |
| `/testkill` | Crash the app on purpose | owner | no |
| `/teststop` | Stop the service (alarm restarts it) | owner | no |
| `/testtemp C`, `/testtemp cpu C` | Fake a temperature for 3 checks | owner | no |

## 8. Log prefixes

| Prefix | Meaning |
|---|---|
| `process started, version` / `service created` / `started: <reason>` | App start and why. |
| `receiver: <action> (uptime N min)` | What woke the app. |
| `config:` | Settings in use (token shown only as set/not set). |
| `camera started` / `camera stopped` / `first frame received` | Camera lifecycle. |
| `motion: N% changed -> <name>` | Motion event. |
| `clip saved <name> <KB> <s>` | Clip finished. |
| `status:` | 10-minute summary. |
| `daily summary sent` | The daily summary went out (counters reset). |
| `battery: … \| cpu N°C` | 1-minute battery and CPU reading. |
| `heat:` | Camera paused/resumed for temperature; `heat: cpu N°C at start …, starting paused`. |
| `testtemp:` / `testkill:` / `teststop:` | Test commands. |
| `telegram: command '…'` / `running late command` / `skipped old command` | Commands received. |
| `telegram: sending X failed (try N, S s)` / `sent X on try N` / `dropped X: …` | Send retries; a send that can't succeed is dropped. |
| `telegram: ignored old non-command message` | Plain chat text that arrived late (no reply). |
| `telegram: ignored message from N: chat type group` / `message from unknown user (id N)` / `refused '/x' from member N (owner only)` / `allowed member N` / `revoked member N` / `over 10 messages a minute` | Roles (Task 8). |
| `telegram: poll failed (N)` / `reachable again after N` / `outage HH:mm-HH:mm, N motions[, cause: …]` | Connection loss and recovery. |
| `net: outage cause: … (wifi …, check page …)` / `net: check page failed: <error>` | After 2 min offline, one check of Wi-Fi + `http://connectivitycheck.gstatic.com/generate_204` (204 = internet fine, other code = login page, no answer = no internet; the error tells DNS from timeout). |
| `telegram: tg-text/tg-media queue full, dropped oldest` | Send queue overflow. |
| `termux: asked to run start-sshd/stop-sshd (<why>)` | SSH on/off requests (`/sshd on N`, `/sshd off`, `auto-off`, `first start since boot`). |
| `storage cap:` / `deleting broken file` / `startup cleanup:` | Storage housekeeping. |
| `no frames for` / `switching to 1px overlay` | Camera watchdog and fallback. |
| `CRASH in thread` | Uncaught exception (stack trace follows). |

## 9. Build, CI and deploy

1. Push to `main` → GitHub Actions (`build.yml`): JDK 17, Gradle 8.10.2, keystore from secrets, `gradle testReleaseUnitTest assembleRelease`. A failing unit test means no APK.
2. Artifact `cctv-apk` (`cctv.apk`), version name `0.4.<run number>`, signed with the fixed key so updates install over the old app.
3. `scripts/deploy.sh` (or `gh run download` + `scp`) copies it to the phone and opens the installer; the phone must be unlocked; tap Install, then Done.
4. `MY_PACKAGE_REPLACED` restarts the app by itself.
