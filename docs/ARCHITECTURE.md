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
| `HeatMonitor.kt` | One sensor's temperature levels with hysteresis → Notify / PauseForHeat / ResumeAfterHeat. | `HeatMonitorTest` (5) |
| `BatteryMonitor.kt` | Battery warnings: temperature (via a HeatMonitor), low %, low voltage, charging stopped/resumed, health change. | `BatteryMonitorTest` (10) |
| `OutageTracker.kt` | Start/end of Telegram outages (≥ 60 s), motions while offline. | `OutageTrackerTest` (3) |
| `LateCommands.kt` | Which late commands still run (settings, read-only) and which are skipped. | `LateCommandsTest` (4) |

### Other files

| File | What it does |
|---|---|
| `android/app/build.gradle.kts` | App build: minSdk 21, targetSdk 22, armeabi-v7a, signing from env vars, test-only deps (JUnit 4.13.2, org.json 20231013). |
| `android/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Pinned AGP 8.7.3, Kotlin 2.1.0; no AndroidX. |
| `AndroidManifest.xml` | Permissions, service, receiver actions (see section 4). |
| `.github/workflows/build.yml` | CI: JDK 17, Gradle 8.10.2, decode keystore secret, `testReleaseUnitTest assembleRelease`, upload `cctv-apk`. |
| `scripts/jvmtest.sh` | Local JVM tests for `core` with `kotlinc` (jars from Maven Central, SHA-1 checked). |
| `scripts/deploy.sh` | Download newest APK, copy to the phone, wait for unlock, open installer. |
| `scripts/set-telegram.sh` | Puts the bot token + your user ID on the phone without printing the token. |

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
| App opened by hand | `MainActivity` → service with `ACTION_OPEN`: first start, or settings reload. |
| Phone boots | `BOOT_COMPLETED` never arrives on this phone. `WIFI_STATE_CHANGED` (or network/charger/unlock/storage events) wakes `BootReceiver` within seconds; if the service isn't running it starts it. Reason "phone rebooted (first signal: …)". |
| App updated | `MY_PACKAGE_REPLACED` → service starts by itself ("app updated"). |
| Service/process dies | Android's START_STICKY restart doesn't happen on this phone. A **1-minute AlarmManager** check starts the service if it isn't running. |
| First start after each boot | Asks Termux to run `start-sshd` (wake lock + sshd). Phase 7 changes this to on-demand. |
| Every start | "🟢 CCTV back online (vX) Why: …" sent, retried until Telegram is reachable. |
| Too hot | A HeatMonitor reaches EMERGENCY → recording stopped, camera closed, watchdog won't restart it; resumes when every sensor is below its resume point. |
| Clip finished | Camera released and reopened (~0.3 s), 1.5 s warm-up, detection resumes. |

## 5. Storage on the phone (`/sdcard/cctv/`)

| Path | What |
|---|---|
| `log.txt`, `log.1.txt` | App log, 1 MB each. |
| `test.jpg` | Latest frame, every 60 s. |
| `clips/YYYY-MM-DD_HH-MM-SS.jpg` | Photo at the moment motion was seen. |
| `clips/YYYY-MM-DD_HH-MM-SS.mp4` | The clip (~2.3 MB per 20 s). |
| `config.json` | Only briefly: dropped there, then moved to app-private storage. |

App-private (`/data/data/com.nandan.cctv/`): `files/config.json` (includes the token), shared prefs `state` (record/alerts/videos switches, `sshdBootMin`).

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

How the levels move (HeatMonitor, hysteresis 2 °C): a level is entered as soon as its threshold is reached; it is left only below an exit point. CPU: WARNING exits below 50, CRITICAL below 53, EMERGENCY below 55 (= resume; the camera restarts and the state drops to CRITICAL if the reading is 53–54.9, without a new message). Battery: exits below 38 / 43 / 42. Messages: on every rise, "❄️ … camera back on" when leaving EMERGENCY, "✅ … normal" on reaching NORMAL.

Change settings: write `/sdcard/cctv/config.json` over SSH, then open the app (`am start -n com.nandan.cctv/.MainActivity`) or send `/reload`. The import **replaces** the whole private config, so include the Telegram keys too.

## 7. Telegram commands

Today only `telegramUserId` (the owner) gets any answer. Phase 6 adds members.

| Command | What it does | After Phase 6 | Runs if it arrives > 2 min late? |
|---|---|---|---|
| `/start`, `/help` | Command list | everyone | yes |
| `/snapshot` | Photo now | member | no (skipped, you're told) |
| `/clip N` | Record N s (1–30) and send it | member | no |
| `/last` | Newest saved clip | member | yes |
| `/status` | Uptime, battery, temps, storage, switches, RAM, Wi-Fi, camera | member | yes |
| `/record on\|off` | Record clips on motion | owner | yes |
| `/alerts on\|off` | Photo alerts on motion | owner | yes |
| `/videos on\|off` | Also send the motion clip | owner | yes |
| `/log` | Last 30 log lines | owner | yes |
| `/reload` | Re-read settings | owner | yes |
| `/sshd` | Ask Termux to start sshd | owner | no |
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
| `battery: … \| cpu N°C` | 1-minute battery and CPU reading. |
| `heat:` | Camera paused/resumed for temperature. |
| `testtemp:` / `testkill:` / `teststop:` | Test commands. |
| `telegram: command '…'` / `running late command` / `skipped old command` | Commands received. |
| `telegram: sending X failed (try N, S s)` / `sent X on try N` | Send retries. |
| `telegram: poll failed (N)` / `reachable again after N` / `outage HH:mm-HH:mm, N motions` | Connection loss and recovery. |
| `telegram: tg-text/tg-media queue full, dropped oldest` | Send queue overflow. |
| `termux:` | sshd start request. |
| `storage cap:` / `deleting broken file` / `startup cleanup:` | Storage housekeeping. |
| `no frames for` / `switching to 1px overlay` | Camera watchdog and fallback. |
| `CRASH in thread` | Uncaught exception (stack trace follows). |

## 9. Build, CI and deploy

1. Push to `main` → GitHub Actions (`build.yml`): JDK 17, Gradle 8.10.2, keystore from secrets, `gradle testReleaseUnitTest assembleRelease`. A failing unit test means no APK.
2. Artifact `cctv-apk` (`cctv.apk`), version name `0.4.<run number>`, signed with the fixed key so updates install over the old app.
3. `scripts/deploy.sh` (or `gh run download` + `scp`) copies it to the phone and opens the installer; the phone must be unlocked; tap Install, then Done.
4. `MY_PACKAGE_REPLACED` restarts the app by itself.
