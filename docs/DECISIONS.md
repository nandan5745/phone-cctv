# Decisions

Format: **decision** (date): why · rejected alternatives · cost if wrong.

## Plan decisions (owner, 2026-11-08)

| Decision | Why | Rejected | Cost if wrong |
|---|---|---|---|
| Roommates use **private chats** with the bot, approved by the owner (`/allow`) | Easy to add/remove one person; photos only go to approved people | Shared group chat (everyone in the group sees photos) | Group convenience lost |
| **SSH on demand** (`/sshd on 30`, auto-off) from Phase 7 | Nothing listening on shared PG Wi-Fi; old frozen OpenSSH | Always-on sshd | After a reboot SSH needs `/sshd on` first |
| Night: **IR remote test + low-light tuning** | Free test first; cheap IR light only if the sensor sees IR | Daytime-only; flash LED all night (bright, hot) | Night coverage may stay poor |
| Timeline **2–4 weeks**; Phases 5–9 on the phone by end of week 3, then a 7-day soak | Resume-ready with measured numbers | All phases incl. TFLite | Some features cut |
| **Cut list:** group chat, `/siren`, `/torch`, certificate pinning, clips in private storage, TFLite person check | Time; documented as future work/residual risks | — | Listed in SECURITY.md later |
| Termux:API ideas done **natively** (TTS, accelerometer) | Termux:API needs Android 7 | Termux:API | — |

## Build choices (2026-11-06)

| Decision | Why | Rejected | Cost if wrong |
|---|---|---|---|
| Kotlin + framework APIs only, **no AndroidX/OkHttp/CameraX** | Memory; recent AndroidX dropped Android 5 | Jetpack libraries | More hand-written code |
| Pinned **Gradle 8.10.2, AGP 8.7.3, Kotlin 2.1.0, JDK 17**, minSdk 21, compileSdk 35 | Known-compatible set that still builds minSdk 21 | Newer, unverified versions | Upgrade work later |
| **targetSdk 22** | Install-time permissions on the 5.1 phone, no runtime prompts | targetSdk 35 + permission code | Lint `ExpiredTargetSdkVersion` disabled |
| Build only on **GitHub Actions**, no Gradle wrapper committed | Mac has no Android SDK and little space | Local Android Studio | Every APK needs a push |
| **Fixed signing key** in GitHub secrets | Updates must install over the old app | Debug key per build | Lose the key → uninstall/reinstall |
| Config dropped on `/sdcard`, **imported into private storage, public copy deleted** | Any app can read `/sdcard` | Config in the repo or on `/sdcard` | — |
| **Long polling** (`getUpdates`, 50 s) | No open port, no public URL | Webhook | One extra request per 50 s |
| Telegram via plain **HttpURLConnection**; token stripped from errors | No libraries; token never logged | OkHttp | — |

## Runtime choices

| Decision (date) | Why | Rejected | Cost if wrong |
|---|---|---|---|
| Old **Camera API**, 640x480, off-screen SurfaceTexture, 2 reused buffers (11-06) | Works with screen off; less memory churn | Camera2 (legacy mode on this phone anyway) | — |
| Motion: 80x60 grid, 2x2 averaging, brightness-normalised, **2 checks in a row** (11-06) | Noise and auto-exposure don't trigger | Single-pixel diff, 1 check | Very short events missed |
| **Reopen the camera after every clip** (11-06) | `reconnect()` hangs ~6 s here | reconnect + startPreview | ~0.3 s gap per clip |
| Complete-MP4 check = boxes add up to file size (11-06) | `moov` is at the start on this phone | "has moov" | — |
| Warnings on **voltage**, not % (11-06) | % is wrong on this phone | % only | — |
| **1-minute AlarmManager** check (11-07) | START_STICKY doesn't restart here | 5-minute check | A wake-up per minute (CPU is awake anyway) |
| **Event-based start** (Wi-Fi/network/charger/unlock/storage) (11-07) | `BOOT_COMPLETED` never arrives (likely low-RAM-mode filtering; mechanism unproven) | Termux:Boot (same block) | — |
| sshd via **Termux RUN_COMMAND** + `allow-external-apps` (11-07, owner OK) | SSH after reboot without Termux:Boot | Termux:Boot | Any app with that permission can run code in Termux |
| **Two send lanes** (text / media) (11-09) | A stuck upload delayed replies 14 min | One queue | One extra thread |
| **Retry until delivered**, queues bounded at 20 (11-09) | Alerts and warnings were lost in outages | Give up after 8 tries | A reply can arrive hours late, in order |
| **Late commands:** settings + read-only run; `/snapshot`, `/clip`, `/sshd`, tests skipped with a reply (11-09) | `/record off` was silently dropped | Skip everything > 2 min | — |
| Watch **battery and CPU** temperature; camera paused while either is in EMERGENCY (11-08) | Battery reading follows the charger | Battery only; CPU only | — |
| CPU levels **52 / 55 / 60, resume 55** (11-11) | Measured: normal 37–50, sun 61.5–63; resume at 50 would flap | 70/80/85 (never reached) | Retune after more data |

## Process choices

| Decision (date) | Why | Rejected |
|---|---|---|
| Pure logic in the **`core` package** with JVM tests (11-08) | Testable without the phone; Android code stays thin | Instrumented tests (no device on CI) |
| **Local tests with `kotlinc`** (`scripts/jvmtest.sh`) (11-08, owner OK) | Seconds instead of a CI round per red/green step | CI-only TDD |
| Work **on `main`**, push each task (11-08) | CI builds only `main`; one commit per task | Feature branches/worktrees |
| **Native execution** of the task plan in one session (11-08, owner choice) | Phone checks need the owner anyway | One subagent per task |
| **One commit per finished task**; docs + plan ticks in the same commit (11-08/11-11) | Clean history, docs stay current | Batch commits |
| Reviews: `/code-review` medium (high for Phases 6–7), **never ultra** | Cost | ultra |
| Karpathy rules **copied as text** into CLAUDE.md, not installed (11-08) | Plain text, nothing to trust | Installing the plugin |

## Rulings from the task ledger

| Task | Ruling | Cost if wrong |
|---|---|---|
| 1 | Write the test runner before the first RED run (plan order was wrong) | none |
| 1 | RED for `lastMean` is a compile error; the other 4 tests passed against existing code | none |
| 2 | Cooling may drop several levels at once (exits: warn < 38, critical < 43, emergency < 42 for the battery); messages only on rise, on normal, on leaving emergency | one message more/less |
| 2 | Invalid temperature = ≤ 0 or > 100 °C (CPU: > 150), invalid voltage = ≤ 0: ignored | a real 0 °C ignored |
| 2 | "Max today" resets at local midnight until Task 5's daily stats | label off by the reset time |
| 2 | Warning texts are prefixes; the service appends the battery details | none |
| 2 | Push before the phone check (CI builds the APK); phone fixes are extra commits in the same task | 2 commits per task sometimes |
| 2 | `/reload` rebuilds the monitors unless heat-paused | a warning may repeat once |
| 2 | HeatMonitor extracted; CPU monitor added | thresholds need tuning (done 11-11) |
| 2 (follow-up) | No unit test for the new CPU defaults (Config is Android; tested in Task 20) | a typo shows only on the phone |
| 3 | Plan amended (owner OK): two lanes, late settings applied, every try logged | — |
| 3 | Text also retries until delivered | stale reply arrives late |
| 3 (close, 11-11) | **Closed "done, partly untested"** (owner): the owner can't cut the Wi-Fi, so retry-until-delivered across a long outage, late settings applied, late actions skipped with a reply and the two lanes under a stuck upload stay unproven on the phone; unit tests + the next natural outage cover them (INCIDENTS S15) | an alert or a late `/record off` misbehaves during a real outage and is only found then |
| 5 | `retentionDays` = days until the cap starts deleting ((cap − used) ÷ rate, ≥ 0): the plan's expected 6.0 is right, its "= cap ÷ rate" comment is a typo | status shows "days until the cap", not "days of footage kept" |
| 5 | `mbPerDay` takes `today` (the incomplete day is left out); days with no files are skipped, not counted as 0 | estimate a bit short after days with recording off (conservative) |
| 5 | `summaryDue` / `day` live in `DailyStats`' companion (no state used); first summary goes out at the first check after 09:00 even right after install | one early summary |
| 5 | `/summary` added to the late-runnable read-only commands (+ test `lateReadOnlyRun`) | none |
| 5 | Daily max temperature = real CPU reading (not battery, not a `/testtemp` fake); `/status` "max today" (midnight reset) left as it is | two different "max" numbers |
| 4 (pre-start, 11-11) | Outage cause enum gains `TELEGRAM_DOWN` (probe 204 = internet fine, Telegram not) | one more case to test |

## Rejected tools

| Tool | Why not |
|---|---|
| Termux:API | Needs Android 7 (all releases v0.43–v0.53) |
| Termux:Boot | Installed, but the boot broadcast never arrives; to be removed |
| Tailscale | No client for Android 5 |
| ECC, gstack, other agent frameworks | Owner's rule: not added |
| Third-party skills (grill-me, security-audit, claude-red) | Duplicate built-in `grilling`, `/security-review`; would need a SkillSpector scan or full read first |
| Node, ffmpeg, NanoHTTPD, OkHttp, AndroidX | Too heavy / unsupported on this phone |
| Full-disk encryption | Android 5 asks for the PIN before boot, breaking unattended recovery |
