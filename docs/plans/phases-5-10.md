# Phases 5–10 Implementation Plan

> **For agentic workers:** the owner chose **native execution**: use superpowers:executing-plans (one session, no subagent per task); see docs/WORKFLOW.md §1. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take the working phone CCTV (v0.4.x) to a safe, multi-user, measurable system: thermal/outage safety, roommate roles, on-demand SSH, better motion detection, two native phone features, tests and measured results.

**Architecture:** All new decision logic goes into a new Android-free package `com.nandan.cctv.core` (plain Kotlin + `org.json`), unit-tested on the JVM. Android classes (`CameraService`, `Bot`, `TelegramApi`, receivers) stay thin and call `core`. Every task ends with a CI run and, where it touches behaviour, a check on the real phone with log/Telegram evidence.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.10.2, Android framework only (minSdk 21 / targetSdk 22), JUnit 4.13.2 + `org.json:json:20231013` (test only), GitHub Actions.

**Spec:** `docs/ROADMAP.md` (public copy of the approved roadmap; the original is a private local file). Repo rules: `CLAUDE.md`. Current position: `docs/STATUS.md`.

## Global Constraints

- Kotlin + Android framework APIs only; no AndroidX, no new runtime libraries.
- minSdk 21, targetSdk 22, `abiFilters += "armeabi-v7a"`.
- Files in `com.nandan.cctv.core` must not import `android.*` (checked by `scripts/jvmtest.sh` failing to compile).
- Never log, print, commit or put in a URL: bot token, keystore, keystore password.
- No IP addresses, usernames, hostnames or SSH details in any repo file.
- One commit per finished task; push to `main` triggers CI (tests, then signed APK).
- "Done" for a phone-facing task = quoted phone log lines or Telegram message.
- Reviews: `/code-review` medium (high for Phases 6 and 7), never ultra.
- Deadline: Tasks 1–17 (Phases 5–9) on the phone by end of week 3.
- Local tests: `kotlinc` is installed on the Mac (Homebrew `kotlin`, 2026-11-08, ~470 MB with its OpenJDK); `scripts/jvmtest.sh` runs the `core` tests in seconds. CI runs them again before every APK.

## Review Focus

1. **Bogus sensor readings** (temperature 0.0 / -1.0, voltage 0) must never trigger heat emergency or low-voltage warnings → Task 2 test `ignoresInvalidReadings`.
2. **Telegram updates without text** (photos, stickers, edited messages, group/channel posts) must be ignored without crashing → Task 7 test `parseReturnsNullForEmptyOrNonCommand` and `groupChatGetsNoRole`.
3. **All zone cells ignored** must not divide by zero or report motion → Task 13 test `allCellsIgnoredGivesZero`.
4. **Phone clock wrong or jumping** (after a reboot without network) must not send the daily summary twice or skip it forever → Task 5 test `summaryDueOncePerDay`.
5. **Corrupt users.json** must fall back to owner-only, not to "nobody" or "everybody" → Task 7 test `corruptJsonFallsBackToOwnerOnly`.

## File map

| File | Responsibility |
|---|---|
| `android/app/src/main/java/com/nandan/cctv/core/*.kt` | Pure logic: `MotionDetector` (moved), `BatteryMonitor`, `OutageTracker`, `NetDiag`, `StorageMath`, `DailyStats`, `Auth`, `Commands`, `RateLimiter`, `ZoneGrid`, `DarknessTracker`, `TamperDetector`, `Metrics` |
| `android/app/src/test/java/com/nandan/cctv/core/*Test.kt` | JUnit 4 tests, one file per core class |
| `scripts/jvmtest.sh` | Compile `core/` + tests with `kotlinc`, run JUnit (fast local loop) |
| `CameraService.kt`, `Bot.kt`, `TelegramApi.kt`, `Termux.kt`, `Storage.kt`, `Config.kt`, `Photo.kt` | Android glue (modified) |
| `phone/termux/start-sshd`, `phone/termux/stop-sshd` | Termux scripts copied to `~/.termux/boot/` |
| `docs/SECURITY.md`, `docs/ARCHITECTURE.md`, `docs/RUNBOOK.md`, `docs/RESULTS.md` | Documentation |

Phone check = `scripts/deploy.sh` (unlock phone, tap Install, Done) then the listed steps; evidence via `ssh phone 'grep … /sdcard/cctv/log.txt'` or the Telegram chat.

---

## Phase 5: Safety and operations

### Task 1: Test infrastructure + MotionDetector tests ✅ (2026-11-08)

**Files:**
- Move: `…/cctv/MotionDetector.kt` → `…/cctv/core/MotionDetector.kt` (package `com.nandan.cctv.core`; update import in `CameraService.kt`)
- Modify: `android/app/build.gradle.kts` (add `testImplementation("junit:junit:4.13.2")`, `testImplementation("org.json:json:20231013")`)
- Modify: `.github/workflows/build.yml` (Build step runs `gradle --no-daemon testReleaseUnitTest assembleRelease`)
- Create: `scripts/jvmtest.sh`, `android/app/src/test/java/com/nandan/cctv/core/MotionDetectorTest.kt`

**Interfaces:**
- Produces: `core` package convention; `MotionDetector.changedShare(nv21: ByteArray, width: Int, height: Int, pixelDiff: Int): Float` unchanged; new read-only `val lastMean: Int` (mean luma of the last frame, used by Task 15).

- [x] **Step 1: Write tests** (`MotionDetectorTest`, frames built by a helper `frame(w=640, h=480, fill: (x, y) -> Int): ByteArray`, Y plane only + zeroed UV)
  - `firstFrameReturnsMinusOne`: first call → `-1f`
  - `identicalFramesGiveZero`: two equal frames → `0f`
  - `uniformBrightnessShiftIgnored`: second frame = first + 40 everywhere → `0f`
  - `blockChangeCounted`: 160×120 px block (= 20×15 cells) changes by +100 → share `== 300f/4800 ± 0.01`
  - `lastMeanReported`: frame filled with 77 → `lastMean == 77`
- [x] **Step 2: Run** `scripts/jvmtest.sh` → `lastMeanReported` FAILS (unresolved reference), others pass (existing behaviour)
- [x] **Step 3: Add `lastMean`**; write `scripts/jvmtest.sh`: `kotlinc` compiles `core/*.kt` + `src/test/**/*.kt` with `junit-4.13.2.jar`, `hamcrest-core-1.3.jar`, `json-20231013.jar` (cached in `~/.cache/cctv-test/`, downloaded from Maven Central on first run) and runs `org.junit.runner.JUnitCore` on every `*Test` class; exits non-zero on failure
- [x] **Step 4: Run** `scripts/jvmtest.sh` → `OK (5 tests)`; push; `gh run watch` → green, log shows `testReleaseUnitTest`
- [x] **Step 5: Commit** `test: core package, JVM test runner, MotionDetector tests`

### Task 2: BatteryMonitor (thermal + existing battery warnings) ✅ (2026-11-08; CPU follow-up phone-checked 2026-11-11)

**Files:**
- Create: `core/BatteryMonitor.kt`, `test/.../core/BatteryMonitorTest.kt`
- Modify: `CameraService.kt` (replace `checkBattery()` body; add heat pause), `Config.kt` (`tempWarnC`, `tempCriticalC`, `tempEmergencyC`, `tempResumeC`), `Bot.kt` (`/testtemp`)

**Interfaces:**
- Produces:
  - `data class BatterySample(val pct: Int, val charging: Boolean, val tempC: Double, val voltageMv: Int, val health: String, val nowMs: Long)`
  - `enum class TempState { NORMAL, WARNING, CRITICAL, EMERGENCY }`
  - `sealed class BatteryEvent { data class Notify(val text: String) : BatteryEvent(); object PauseForHeat : BatteryEvent(); object ResumeAfterHeat : BatteryEvent() }`
  - `class BatteryMonitor(warnC: Double = 40.0, criticalC: Double = 45.0, emergencyC: Double = 50.0, resumeC: Double = 42.0, hysteresisC: Double = 2.0, lowPct: Int = 30, lowVoltageMv: Int = 3600)` with `fun update(s: BatterySample): List<BatteryEvent>`, `val tempState: TempState`, `val maxTempC: Double`, `fun resetMax()`
- Consumes: `BatteryInfo` (Android) → mapped to `BatterySample` in `CameraService`.

- [x] **Step 1: Write tests**
  - `warningAt40`: 39.9 → no event; 40.0 → one `Notify` whose text starts with `"🌡️ Battery warm"`, state WARNING
  - `noRepeatWhileWarm`: three samples at 41 → exactly one Notify total
  - `hysteresisOnCooling`: WARNING then 39.0 → still WARNING, no Notify; 37.9 → Notify starting `"✅ Battery temperature normal"`
  - `criticalAt45`: → Notify starting `"🔥 Battery HOT"`
  - `emergencyPausesAndResumes`: 50.0 → `[Notify("🚨 …"), PauseForHeat]`; 43.0 → nothing; 41.9 → contains `ResumeAfterHeat`
  - `ignoresInvalidReadings`: temp 0.0, voltage 0 → no events, state NORMAL
  - `lowVoltageOnceWithReset`: 3590 mV → one Notify `"🪫 Battery voltage LOW"`; 3595 → none; 3760 → none; 3590 → Notify again
  - `chargingStoppedAndResumed`: charging true→false → Notify `"⚠️ Phone is not charging"`; false→true → `"🔌 Charging again"`
  - `healthChange`: health `good`→`OVERHEAT` → Notify containing `"health: OVERHEAT"`
  - `maxTempTracked`: samples 30, 36, 33 → `maxTempC == 36.0`; `resetMax()` → next sample sets it
- [x] **Step 2: Run** `scripts/jvmtest.sh` → FAIL (`BatteryMonitor` unresolved)
- [x] **Step 3: Implement `BatteryMonitor`** (state rises immediately; falls one level only when below that level's threshold minus `hysteresisC`; leaving EMERGENCY requires `< resumeC`). Move the existing "dropping while plugged in" check from `CameraService.checkBattery()` unchanged into `CameraService` (it uses `%`, kept as is).
- [x] **Step 4: Run** → all pass
- [x] **Step 5: Wire** `CameraService.checkBattery()`: build sample, `override` temp from `/testtemp` if set (`/testtemp <C>` = fake temperature for the next 3 checks), send each `Notify` via `bot.send`, on `PauseForHeat` → `stopRecording(); stopCamera(); heatPaused = true` (watchdog skips `startCamera()` while `heatPaused`), on `ResumeAfterHeat` → `heatPaused = false; startCamera()`. `/status` shows `Temp: 29.0 °C (max today 31.5)`.
- [x] **Step 6: Phone check:** `/testtemp 41` → Telegram "🌡️ Battery warm"; `/testtemp 51` → "🚨" + log `camera stopped` and no `camera started` for 3 min; after the override ends → log `camera started` + "✅ Battery temperature normal"
- [x] **Step 7: Commit** `feat: thermal states with heat pause (BatteryMonitor)`

**Task 2 follow-ups**
- 2026-11-08 (user-approved): battery temperature follows the charger, so the processor sensor (`sensor_arm`) is watched too through `core/HeatMonitor.kt`; camera pauses while either sensor is in EMERGENCY; `/testtemp cpu C`. First defaults 70/80/85, resume 75.
- 2026-11-11 (user-measured): CPU thresholds lowered to **warn 52 / critical 55 / emergency 60 °C, resume 55** (log Nov 8–11: normal 37–50 °C, ~10% of minutes at ~50, 51–54 only ~35 min in 3 days; 61.5–63 °C in direct sun). Phone check: log `config: … cpu=52.0/55.0/60.0/55.0°C`; `/testtemp cpu 61` → "🚨 Phone CPU 61.0 °C: camera stopped", camera back once the real reading is below 55. **Done 2026-11-11 (0.4.17):** `22:47:47 config: … cpu=52.0/55.0/60.0/55.0°C`; `22:49:36 heat: cpu 61.0°C, pausing camera` → `22:49:37 camera stopped`; `22:51:48 heat: cpu cooled to 41.017°C, resuming camera` → `22:51:49 camera started 640x480`.

### Task 3: Alerts survive outages + offline summary (+ fixes from the 2026-11-08 incidents) ✅ (done 2026-11-11, partly untested on the phone)

Evidence: 18:12 `/snapshot` photo upload failed with `EOFException` 3+ times and every text reply waited behind it until ~18:26; outages 20:18–22:59 and 23:48–03:29 made `/record off`, `/alerts off`, `/videos off` arrive ~80 min late and the 2-minute rule skipped them silently; 22 alert photos/videos were dropped after 8 tries.

**Files:** Create `core/OutageTracker.kt`, `core/LateCommands.kt` + tests; Modify `Bot.kt`, `CameraService.kt` (`bot.noteMotion()` in `onMotion`)

**Interfaces:**
- Produces: `data class Outage(val startMs: Long, val endMs: Long, val motions: Int)`; `class OutageTracker(minOutageMs: Long = 60_000)` with `fun onFailure(nowMs: Long)`, `fun onSuccess(nowMs: Long): Outage?`, `fun noteMotion()`, `val offline: Boolean`
- `Bot.sendPhoto` / `Bot.sendVideo` retry with `maxTries = Int.MAX_VALUE` (queue stays 20, DiscardOldest; log `telegram: queue full, dropped oldest`).
- **Two send lanes:** `tg-text` (messages) and `tg-media` (photos, videos), each its own single thread and bounded queue, so a stuck upload never delays a text reply.
- **Every failed try is logged** with its duration: `telegram: sending photo failed (try 4, 71 s): EOFException: null`.
- `enum class LateAction { RUN, SKIP }`; `object LateCommands { val RUN_WHEN_LATE = setOf("/record", "/alerts", "/videos", "/status", "/help", "/start", "/log", "/last", "/reload"); fun action(name: String, ageS: Long, maxAgeS: Long = 120): LateAction }`. A skipped command gets the reply `"⏭ Skipped /snapshot: sent 80 min ago while I was offline."`; a late one that runs is logged `telegram: running late command '/record off' (4808s old)`.

- [x] **Step 1: Tests:** `shortBlipIsNotAnOutage` (fail at 0, success at 30 s → `null`); `outageReportedOnce` (fail 0, fail 70 s, success 90 s → `Outage(0, 90000, 0)`; next success → `null`); `motionsCountedOnlyWhileOffline` (2 motions online, fail, 3 motions, success after 2 min → `motions == 3`); `freshCommandsRun` (any name, age 5 s → RUN); `lateSettingsRun` (`/record`, `/alerts`, `/videos` at 4808 s → RUN); `lateActionsSkipped` (`/snapshot`, `/clip`, `/sshd`, `/testkill`, `/testtemp` at 4808 s → SKIP); `boundaryIsInclusive` (`/clip` at exactly 120 s → RUN, 121 s → SKIP)
- [x] **Step 2: Run** → FAIL
- [x] **Step 3: Implement**; in `Bot.pollLoop` call `onFailure`/`onSuccess`; on an `Outage` send `"📴 Was offline HH:mm–HH:mm (N min), M motions while offline"`
- [x] **Step 4: Run** → pass
- [x] **Step 5: Phone check — partly done, rest accepted as untested (owner, 2026-11-11: the Wi-Fi can't be cut).** Seen: outage summaries incl. motion count (`outage 14:09-17:51, 3 motions`), short text/media retries. Not seen: alert delivered after a long outage, `running late command`, new-style `skipped old command` + reply, lanes under a stuck upload (docs/INCIDENTS.md S15). The planned re-test, kept for a future natural outage: `/alerts on`, `/record on`; cut the home internet (router WAN or router off); walk past; from mobile data send `/videos off` and `/snapshot`; keep the internet off **≥ 3 more minutes after sending** (so they arrive > 2 min old); restore → Telegram: the delayed alert photo, "📴 Was offline …, ≥1 motions while offline", "Motion videos: OFF" + log `running late command '/videos off'`, "⏭ Skipped /snapshot …" + log `skipped old command`; log has no `queue full`. Then `/videos on`. (A first cut test on 2026-11-11 had alerts OFF and fresh commands, so it proved only the outage summary.)
- [x] **Step 6: Commit**: code shipped in `47824b0` (0.4.16). Closed 2026-11-11 (docs commit; INCIDENTS O2 → S15).

### Task 4: Network diagnostics + IP in status ✅ (done 2026-11-11; outage-cause line untested until a real outage)

Context: unexplained outages to diagnose — Nov 7 17:11–18:27, Nov 8 20:18–22:59 and 23:48–03:29, **Nov 11 14:09–17:51 (3 h 42 min, 3 motions, cause unknown)**; short blips Nov 9 18:22 and Nov 11 06:44 were Telegram HTTP 502 (server side).

**Files:** Create `core/NetDiag.kt` + test; Modify `Bot.kt`, `CameraService.kt` (`statusText()`, back-online text)

**Interfaces:**
- Produces: `fun formatIpv4(littleEndian: Int): String`; `enum class OutageCause { WIFI_DOWN, CAPTIVE_PORTAL, NO_INTERNET, TELEGRAM_DOWN }`; `fun classify(wifiConnected: Boolean, probeHttpCode: Int?): OutageCause` with probe = HTTP GET `http://connectivitycheck.gstatic.com/generate_204` during an outage: Wi-Fi not connected → `WIFI_DOWN`; probe got no response (`null`) → `NO_INTERNET`; probe `204` → `TELEGRAM_DOWN` (internet works, Telegram doesn't, e.g. HTTP 502); any other code (200, 302 …) → `CAPTIVE_PORTAL`. (Ruling 2026-11-11: `TELEGRAM_DOWN` added to make the 204 case unambiguous.)
- `Outage` summary text gains the cause.

- [x] **Step 1: Tests:** `formatsLittleEndianIp` (`0x097100CB` → `"203.0.113.9"`, a documentation-only address); `classifyWifiDown` (`classify(false, 204)` and `classify(false, null)` → `WIFI_DOWN`); `classifyPortal` (`classify(true, 200)`, `classify(true, 302)` → `CAPTIVE_PORTAL`); `classifyNoInternet` (`classify(true, null)` → `NO_INTERNET`); `classifyTelegramDown` (`classify(true, 204)` → `TELEGRAM_DOWN`)
- [x] **Step 2: Run** → FAIL. **Step 3: Implement**; probe runs once on the poller thread after 2 min offline. **Step 4: Run** → pass (34 tests; extra: `formatsHighBytes`, `OutageTracker.offlineMs` + `offlineTimeCountsFromFirstFailure`; Wi-Fi state from `WifiManager`, no new permission; back-online text built at send time so a fresh boot shows the IP)
- [x] **Step 5: Phone check (2026-11-11, 0.4.21):** IP part done: owner confirmed the back-online message and `/status` show `Wi-Fi: <network>, IP …` (log `22:57:32 process started, version 0.4.21`, `started: app updated`). Outage-cause part **untested until a real outage**. Planned check: `/status` Wi-Fi line changes from `Wi-Fi: -77 dBm (3/4)` to `Wi-Fi: <ssid>, IP <addr>, -77 dBm (3/4)`; "back online" shows the IP; next real outage (or a Wi-Fi toggle on the phone, if the owner can) → summary names the cause, e.g. `(cause: no internet)`
- [x] **Step 6: Commit** `feat: network diagnostics, IP in status` (`70503af`) + docs close commit

### Task 5: Storage dashboard + daily summary

**Files:** Create `core/StorageMath.kt`, `core/DailyStats.kt` + tests; Modify `Storage.kt`, `CameraService.kt`, `Bot.kt` (`/summary`), `Config.kt` (`dailySummaryHour`, default 9)

**Interfaces:**
- `fun retentionDays(usedMb: Long, capMb: Int, mbPerDay: Double): Double?` (null when `mbPerDay <= 0`); `fun mbPerDay(sizesByDay: Map<String, Long>): Double` (mean of the last 3 complete days)
- `class DailyStats` (serialisable to JSON in prefs `"daily"`): counters `motions, clips, alerts, restarts, offlineMin`, `maxTempC`; `fun summaryText(storageLine: String): String`; `fun reset()`; `fun summaryDue(nowMs: Long, hour: Int, lastSentDay: String?, tz: TimeZone): Boolean`
- `Storage.summary(): StorageSummary(clips: Int, photos: Int, usedMb: Long, oldest: String?, newest: String?, sizesByDay: Map<String, Long>)`

- [x] **Step 1: Tests:** `retentionFromRate` (600 used, cap 1200, 100/day → `6.0` = cap ÷ rate); `noRateNoEstimate`; `mbPerDayUsesLastThreeCompleteDays`; `summaryDueOncePerDay` (due at 09:00 when last sent yesterday; not due again same day; not due at 08:59; a clock jump back to yesterday with `lastSentDay` = today → not due); `summaryTextHasAllCounters`
- [x] **Step 2: Run** → FAIL. **Step 3: Implement**; `CameraService` increments counters, checks `summaryDue` every minute in the battery watcher, sends, resets, stores `lastSentDay`. `/summary` sends it immediately without resetting. **Step 4: Run** → pass (41 tests; rulings: `retentionDays` = days until the cap, `mbPerDay(…, today)`, `summaryDue` in the companion, `/summary` runs when late, max temp = real CPU reading — docs/DECISIONS.md)
- [ ] **Step 5: Phone check:** `/status` shows `Storage: 590 files (148 clips, 442 photos), 135/1200 MB, oldest 2026-11-06, ~N days left`; `/summary` returns the report; next day at 09:00 the summary arrives unprompted (evidence: Telegram message time)
- [ ] **Step 6: Commit** `feat: storage dashboard and daily summary`

### Task 6: Housekeeping + Phase 5 review

**Files:** Modify `AndroidManifest.xml` (remove `QUICKBOOT_POWERON`)

- [ ] **Step 1:** Remove the action; push; CI green
- [ ] **Step 2: Phone:** `ssh phone 'am start -a android.intent.action.DELETE -d package:com.termux.boot'` → you tap OK; `pm list packages | grep termux` shows only `com.termux`
- [ ] **Step 3:** `/code-review medium` on the Phase 5 diff; fix findings (each fix = its own test + commit)
- [ ] **Step 4: Phone check:** reboot → "back online … phone rebooted" (proves the receiver still works without that action)
- [ ] **Step 5: Commit** `chore: drop unprotected QUICKBOOT action`

---

## Phase 6: Multiple users and roles

### Task 7: Auth, Commands, RateLimiter (pure)

**Files:** Create `core/Auth.kt`, `core/Commands.kt`, `core/RateLimiter.kt` + tests

**Interfaces:**
- `enum class Role { OWNER, MEMBER }`; `data class User(val id: Long, val name: String, val role: Role)`
- `class Auth(ownerId: Long, members: List<User> = emptyList())`: `fun roleOf(userId: Long, chatType: String): Role?` (null unless `chatType == "private"`), `fun allow(id: Long, name: String): Boolean` (false if already present or owner), `fun revoke(id: Long): Boolean` (false for owner), `fun users(): List<User>` (owner first), `fun toJson(): String`, `companion fun fromJson(ownerId: Long, json: String?): Auth`
- `data class Command(val name: String, val arg: String?, val rest: String)`; `object Commands { val MEMBER = setOf("/start", "/help", "/snapshot", "/clip", "/last", "/status"); fun parse(text: String?): Command?; fun allowed(role: Role, name: String): Boolean }` (OWNER → everything; MEMBER → only `MEMBER`; so `/testkill`, `/teststop`, `/testtemp`, `/testcamera`, `/summary`, `/speak` are owner-only automatically)
- `class RateLimiter(val max: Int, val windowMs: Long)`: `fun tryAcquire(key: Long, nowMs: Long): Boolean`

- [ ] **Step 1: Tests:**
  - Auth: `ownerHasRoleInPrivate`; `groupChatGetsNoRole` (owner in `"group"`/`"supergroup"`/`"channel"` → null); `unknownUserGetsNull`; `allowThenMember`; `revokeOwnerFails`; `jsonRoundTrip`; `corruptJsonFallsBackToOwnerOnly` (`"{oops"` → only owner, `roleOf(other)` null)
  - Commands: `parseStripsBotSuffixAndLowercases` (`"/Clip@cctv_bot 5"` → `Command("/clip", "5", "5")`); `parseKeepsRestForSpeak` (`"/speak hello there"` → `rest == "hello there"`); `parseReturnsNullForEmptyOrNonCommand` (`null`, `""`, `"hello"`); `memberCannotUseOwnerCommands` (`/record`, `/testkill`, `/teststop`, `/testtemp`, `/sshd`, `/allow`, `/speak` → false); `memberCanUseMemberCommands`
  - RateLimiter: `allowsUpToMaxInWindow` (max 10 / 60 s: 10 true, 11th false); `windowSlides` (after 60 s → true again); `keysIndependent`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → pass
- [ ] **Step 5: Commit** `feat: core auth, command parsing, rate limiter`

### Task 8: Role-based routing in the bot

**Files:** Modify `Bot.kt`, `CameraService.kt` (Control methods take `chatId`), `TelegramApi.kt` (no change expected)

**Interfaces:**
- Consumes: Task 7 types.
- `Bot.Control` becomes: `snapshot(chatId: Long)`, `clip(seconds: Int, chatId: Long)`, `last(chatId: Long)`, `status(chatId: Long)`, others unchanged.
- `Bot.send(chatId: Long, text: String)`; `Bot.broadcast…` variants send alerts/videos/warnings to every user in `auth.users()`.
- Users persisted at `filesDir/users.json` (written on allow/revoke).

- [ ] **Step 1:** Route: `roleOf` null → if `RateLimiter(1, 24h)` allows that user → owner gets `"👤 Unknown user <first_name> (id N) wrote to the bot. /allow N to add them."`; no reply to the stranger. Role present → `Commands.allowed` false → reply `"Only the owner can do that."`. Per-user `RateLimiter(10, 60 s)` → over limit: ignore + log once. Replies and command results go to the requester's chat. `/allow N`, `/revoke N`, `/users` implemented; added member gets `"✅ You can now use this camera. Send /help."`
- [ ] **Step 2:** `scripts/jvmtest.sh` still green; push; CI green
- [ ] **Step 3: Phone check** (second Telegram account): first message → owner notice with its id, stranger gets nothing; `/allow <id>` → member gets the welcome; member `/snapshot` works, member `/record off` → "Only the owner can do that."; walk past → **both** accounts get the alert; member adds bot to a group and sends `/status` there → no reply (log `ignored … chat type group`); `/revoke <id>` → member ignored again
- [ ] **Step 4: Commit** `feat: roommates with roles and approval flow`

### Task 9: Phase 6 review + SECURITY.md draft

**Files:** Create `docs/SECURITY.md` (roadmap section 4 table expanded: attackers, surfaces, scenarios, protections, residual risks)

- [ ] **Step 1:** Write `docs/SECURITY.md`
- [ ] **Step 2:** `/code-review high` on Phase 6 diff, then `/security-review`; each finding → fixed (test + commit) or added to SECURITY.md "Accepted risks" with a reason
- [ ] **Step 3: Commit** `docs: threat model; fixes from phase 6 review`

---

## Phase 7: Security hardening

### Task 10: SSH on demand

**Files:** Create `phone/termux/start-sshd`, `phone/termux/stop-sshd`; Modify `Termux.kt`, `CameraService.kt`, `Bot.kt`, `Config.kt` (`sshdAtBoot`, default `false`)

**Interfaces:**
- `Termux.run(ctx: Context, script: String, why: String): Boolean` (script name only, resolved under `~/.termux/boot/`; replaces `startSshd`)
- `Commands` arg: `/sshd on [minutes]` (default 30, clamped 5..120), `/sshd off`. Parsing helper `fun sshdMinutes(arg: String?, rest: String): Int?` in `core/Commands.kt` (null = off).
- Auto-off deadline stored in prefs `"sshdUntil"`; the 1-minute battery watcher runs `stop-sshd` when past it (survives service restarts).

- [ ] **Step 1: Tests** (`CommandsTest`): `sshdOnDefault30`; `sshdOnClamped` (`"on 500"` → 120, `"on 1"` → 5); `sshdOffIsNull`; `sshdGarbageDefaults30`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; `stop-sshd` kills the PID in `$PREFIX/var/run/sshd.pid` (existing sessions survive); copy both scripts to the phone over SSH **before** installing the APK. **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** `/sshd off` → `nc -z` to the phone's SSH port fails from the Mac; `/sshd on 5` → `ssh phone true` works; 6 min later → fails again (log `termux: … stop-sshd (auto-off)`); reboot → SSH closed until `/sshd on`
- [ ] **Step 6: Commit** `feat: SSH on demand with auto-off`

### Task 11: Hardening checklist + status line

**Files:** Modify `CameraService.kt` (`/status` line `Radios: Bluetooth off`), `AndroidManifest.xml` (`android.permission.BLUETOOTH`, normal permission), `docs/SECURITY.md` (phone checklist)

- [ ] **Step 1:** Implement; push; CI green
- [ ] **Step 2: You on the phone:** Bluetooth off, NFC off (if present), location off, mobile data off, screen PIN set, unused apps disabled
- [ ] **Step 3: Phone check:** `/status` shows `Radios: Bluetooth off`
- [ ] **Step 4:** `/code-review high` + `/security-review` against `docs/SECURITY.md`; fixes or accepted risks
- [ ] **Step 5: Commit** `feat: radio status; security checklist`

---

## Phase 8: Better motion detection

### Task 12: Design grilling

- [ ] Run `grilling` on Tasks 13–15 (zones, background model, darkness) with the morning curtain data (445 triggers 06:40–09:16); record decisions changed by it at the top of this file before coding.

### Task 13: Ignore zones

**Files:** Create `core/ZoneGrid.kt` + test; Modify `core/MotionDetector.kt`, `Photo.kt` (`withGrid`), `Bot.kt` (`/zones`, `/ignore`, `/watch`), `CameraService.kt` (mask in prefs `"zones"`)

**Interfaces:**
- `class ZoneGrid(val cols: Int = 8, val rows: Int = 6)`: labels `A1..H6` (letter = column left→right, number = row top→bottom, **in the upright photo**); `fun parse(labels: String): Set<Int>?` (null if any label invalid); `fun label(cell: Int): String`; `fun sensorMask(ignored: Set<Int>, rotation: Int, gridW: Int = 80, gridH: Int = 60): BooleanArray` (true = ignore that detector cell, mapped from upright to sensor orientation)
- `MotionDetector.changedShare(nv21, width, height, pixelDiff, ignore: BooleanArray? = null): Float` (share over non-ignored cells)
- `Photo.withGrid(jpeg: ByteArray, rotation: Int, label: String, cols: Int, rows: Int, ignored: Set<Int>): ByteArray` (grid lines + labels, ignored cells shaded)

- [ ] **Step 1: Tests:** `parsesLabels` (`"a1 C2 h6"` → `{0, 10, 47}`); `rejectsBadLabel` (`"Z9"` → null); `rotation90MapsUprightTopLeftToSensorBottomLeft` (ignore `A1`, rotation 90 → mask true at sensor cell `(x=0, y=59)` area, false at `(0,0)`); `rotation0Identity`; `ignoredCellsExcludedFromShare` (change only inside an ignored region → `0f`); `allCellsIgnoredGivesZero`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** `/zones` → photo with grid; `/ignore <cells over the curtain>` → reply lists them; next morning's status lines: motions 06:30–09:30 vs the 445 baseline (record both numbers in `docs/RESULTS.md`)
- [ ] **Step 6: Commit** `feat: ignore zones`

### Task 14: Background model

**Files:** Modify `core/MotionDetector.kt` + test

**Interfaces:** `MotionDetector(cols, rows, alpha: Float = 0.05f)`; compares the current frame to a running-average background (`bg += (cur - bg) * alpha`, mean-normalised as now) instead of the previous frame; `reset()` re-seeds from the next frame.

- [ ] **Step 1: Tests:** `slowLocalBrighteningIgnored` (a region brightening +1 level per frame for 100 frames → share stays `< 0.02`); `personSizedBlockTriggers` (20×15-cell block appears → share `> 0.05`); `stillObjectAbsorbed` (block stays → after 90 frames share `< 0.02`); existing tests still pass
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** walk past → alert within the same delay as before (log `motion:` within 2 s of the walk); 24 h status lines: count false triggers vs Task 13's number
- [ ] **Step 6: Commit** `feat: background model for motion`

### Task 15: Darkness detection + low-light tuning + IR test

**Files:** Create `core/DarknessTracker.kt` + test; Modify `CameraService.kt`, `Config.kt` (`darkLuma`, default 20)

**Interfaces:** `class DarknessTracker(threshold: Int, holdMs: Long = 300_000)`: `fun update(meanLuma: Int, nowMs: Long): DarkEvent?` with `enum class DarkEvent { BECAME_DARK, BECAME_LIGHT }` (needs `holdMs` continuously below/above `threshold + 5` to switch)

- [ ] **Step 1: Tests:** `darkAfterHold`; `briefDarknessIgnored`; `lightNeedsMarginAndHold`; `noRepeatEvents`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; on `BECAME_DARK`: notice `"🌑 Too dark to see since HH:mm"`, set preview fps range to the supported range with the lowest minimum and exposure compensation to max, log `low-light: fps=…, ev=…, mean before=…`; on `BECAME_LIGHT`: restore and `"☀️ Camera can see again"`. `/status` shows `Light: dark (mean 8)`. **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** cover the camera 6 min → 🌑 notice; uncover → ☀️; at night, log line with mean luma before/after tuning (record in RESULTS.md). **IR test (you):** in the dark, point a TV remote at the camera, hold a button, send `/snapshot` → note whether the LED shows
- [ ] **Step 6:** `/code-review medium` on Phase 8; **Commit** `feat: darkness detection and low-light tuning`

---

## Phase 9: Native phone features

### Task 16: /speak

**Files:** Create `Speaker.kt` (Android `TextToSpeech` wrapper); Modify `Bot.kt`, `CameraService.kt`

**Interfaces:** `class Speaker(ctx: Context)`: `fun say(text: String)` (init on first use, max 200 chars, released after 60 s idle); `/speak <text>` owner-only (Task 7 rules) uses `Command.rest`.

- [ ] **Step 1:** Implement; push; CI green
- [ ] **Step 2: Phone check:** `/speak hello from the bot` → you hear it; log `tts: spoke 19 chars`; `/speak` with no text → usage reply; memory in next status line within 5 MB of before
- [ ] **Step 3: Commit** `feat: /speak via Pico TTS`

### Task 17: Tamper alert

**Files:** Create `core/TamperDetector.kt` + test; Modify `CameraService.kt` (accelerometer listener, `SENSOR_DELAY_NORMAL`)

**Interfaces:** `class TamperDetector(thresholdG: Float = 0.35f, cooldownMs: Long = 60_000, alpha: Float = 0.02f)`: `fun update(x: Float, y: Float, z: Float, nowMs: Long): Boolean` (true = tamper; compares the reading to a slow moving-average baseline, values in m/s²)

- [ ] **Step 1: Tests:** `steadyGravityNoTamper` (1000 readings of (0, 0, 9.81)); `knockTriggers` (one reading +0.5 g → true); `cooldownSuppressesRepeat`; `slowTiltAbsorbed` (rotate gravity 1° per reading over 30 readings → false)
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; on tamper → alert photo to all users `"📳 Phone moved or knocked"`. **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** tap the phone → alert; leave 1 h → zero tamper alerts (status line counter)
- [ ] **Step 6:** `/code-review medium`; **Commit** `feat: tamper alert`

**Checkpoint: end of week 3: Tasks 1–17 installed and verified. Start the 7-day soak now (Task 22).**

---

## Phase 10: Tests, measurements, documentation

### Task 18: /simplify CameraService

- [ ] Run `/simplify` on `CameraService.kt` (no behaviour change); `scripts/jvmtest.sh` + CI green; phone smoke check: walk past → photo + clip, `/status` OK; **Commit** `refactor: simplify CameraService`

### Task 19: Metrics + alert latency + /metrics

**Files:** Create `core/Metrics.kt` + test; Modify `CameraService.kt`, `Bot.kt` (`/metrics`), `TelegramApi.kt` (`sendDocument(chatId: Long, file: File, caption: String)`, multipart like `sendVideo`, mime `text/csv`)

**Interfaces:** `fun cpuPercent(prevTicks: Long, ticks: Long, prevWallMs: Long, wallMs: Long, hz: Int = 100): Double`; `fun median(values: List<Long>): Long?`; `object MetricsCsv { const val HEADER = "time,uptime_s,restarts,ram_free_mb,heap_mb,cpu_pct,temp_c,voltage_mv,motions,clips,alert_latency_ms_median"; fun row(…): String }`

- [ ] **Step 1: Tests:** `cpuFromTicks` (50 ticks over 1000 ms at 100 Hz → `50.0`); `medianOddEven`; `medianEmptyIsNull`; `rowMatchesHeaderColumns` (comma count equal)
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; every 10 min append a row to `/sdcard/cctv/metrics.csv`; alert latency = ms from motion detected to `sendPhoto` HTTP success (logged per alert `alert latency 1840 ms`). **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** after 30 min `/metrics` → CSV file in Telegram with ≥3 rows; log has `alert latency` lines after a walk-past
- [ ] **Step 6: Commit** `feat: metrics CSV and alert latency`

### Task 20: Remaining unit tests

**Files:** Modify `Config.kt` (expose `internal fun Config.Companion.fromJson(j: JSONObject): Config` for tests, move into `core/ConfigParse.kt` if it imports Android), `Storage.kt` (`isCompleteMp4` → `core/Mp4Check.kt`, `fun isCompleteMp4(f: File): Boolean`)

- [ ] **Step 1: Tests:** `configDefaults`; `configClampsOutOfRange` (`clipSeconds: 999` → 60, `analyzeFps: 0` → 1); `configToStringHidesToken`; `mp4CompleteWhenBoxesSumToLength`; `mp4TruncatedIsBroken`; `mp4ZeroSizeBoxIsBroken`; `emptyFileIsBroken`
- [ ] **Step 2: Run** → FAIL (functions not in core yet). **Step 3: Move/implement.** **Step 4: Run** → pass
- [ ] **Step 5: Phone check:** plant a truncated mp4 in `clips/`, `/testkill` → log `deleting broken file …` on restart
- [ ] **Step 6: Commit** `test: config and mp4 checks`

### Task 21: Fault-injection runbook

**Files:** Create `docs/RUNBOOK.md`; Modify `Bot.kt` (`/testcamera` owner-only: closes the camera without notice; watchdog must recover)

- [ ] **Step 1:** Add `/testcamera`; CI green
- [ ] **Step 2:** Write `docs/RUNBOOK.md`: for each fault (crash `/testkill`, stop `/teststop`, camera `/testcamera`, reboot, Wi-Fi off 5 min, internet off 5 min, low storage via `minFreeMB: 100000`, overheat `/testtemp 51`) the steps, expected evidence and how to measure recovery time from log timestamps
- [ ] **Step 3:** Run every fault on the phone; record measured recovery times in `docs/RESULTS.md` (table: fault, recovery s, evidence line)
- [ ] **Step 4: Commit** `docs: fault-injection runbook and results`

### Task 22: 7-day soak + documentation + interview prep

**Files:** Create `docs/ARCHITECTURE.md` (pipeline diagram from the roadmap section 3, lifecycle, threads), finish `docs/RESULTS.md`, `docs/SECURITY.md`; update `README.md` links

- [ ] **Step 1:** Soak starts at the week-3 checkpoint; no installs during it except fixes (each fix restarts the 7-day count, noted in RESULTS.md)
- [ ] **Step 2:** From `metrics.csv` + daily summaries: uptime %, restarts by reason, median/95th alert latency, RAM/heap range, max temp, false triggers/day, storage/day. `superpowers:verification-before-completion`: every number in RESULTS.md cites its source rows
- [ ] **Step 3:** Fill the resume bullets in RESULTS.md with measured numbers only
- [ ] **Step 4:** `grilling` on RESULTS.md (interview prep); write down weak answers as follow-ups
- [ ] **Step 5: Commit** `docs: architecture, results, soak report`
