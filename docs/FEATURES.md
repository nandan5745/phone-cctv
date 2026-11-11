# Features and how far each is tested

Every feature and command so far (Phases 1–5). **Tested on phone?** yes = seen working on the real phone; partly = some paths seen, others not; no = never seen on the phone. Evidence is a log line from `/sdcard/cctv/log.txt` (date + time) or a Telegram message. Update this file when a feature is added or tested (CLAUDE.md, "Keep docs current").

## Camera, motion, recording (Phases 1–2)

| Feature | How it works (1 line) | Tested on phone? | Evidence | Notes |
|---|---|---|---|---|
| Camera service, screen off | Foreground service opens the back camera at 640x480 into an off-screen SurfaceTexture | yes | 2026-11-06 16:20:04 `camera started 640x480, surface=SurfaceTexture` | Runs for days with the screen off (29 h without a restart, Nov 7–8) |
| Motion detection | Y plane → 80x60 grid, brightness-normalised diff, 2 checks in a row | yes | 2026-11-06 20:11:10 `motion: 19.9% changed -> 2026-11-06_20-11-10` | Curtain/daylight false triggers (INCIDENTS O3); blind in the dark (O4) |
| Motion photo | Rotated JPEG saved at the moment of motion | yes | Photos in `clips/*.jpg` since 2026-11-06 | ~35–45 KB each |
| 20 s clip recording | MediaRecorder, hardware H.264, 700 kbps set (~920 real) | yes | 2026-11-06 20:11:32 `clip saved 2026-11-06_20-11-10.mp4 2290 KB 21s` | `/record off` stops it |
| Reopen camera after each clip | Close + reopen (~0.3 s) instead of `reconnect()` | yes | `camera stopped` / `camera started` pairs after every clip | INCIDENTS S1 |
| Storage cap | Deletes oldest files above 1200 MB | yes | 2026-11-06 20:16:15 `storage cap: deleted 1 oldest files, clips now 49 MB` | Seen with a lowered test cap |
| Minimum free space | No recording below 300 MB free | no | — | Disk has never been that full |
| Broken clip cleanup | At start, deletes `.mp4` whose boxes don't add up to the file size, `.tmp`, 0-byte files | yes | 2026-11-06 20:15:20 `startup cleanup: deleted 2 broken files` | INCIDENTS S2 |
| Status line in the log | RAM, motion stats, storage, battery every 10 min | yes | Every 10 min, e.g. 2026-11-11 14:05:46 `status: ram free 164/471 MB …` | |
| Battery + CPU log | One `battery:` line per minute incl. CPU temperature | yes | 2026-11-11 22:49:48 `battery: 100% full (AC) 25.1°C 4.37V health=good \| cpu 61.0°C` | |
| Latest frame `test.jpg` | Overwritten every 60 s | yes | `/sdcard/cctv/test.jpg` modified 2026-11-11 22:48 | |
| Log file with rotation | `log.txt` up to 1 MB, then `log.1.txt` | partly | `log.txt` 876 KB on 2026-11-11 | Rotation not reached yet |
| Config import | `/sdcard/cctv/config.json` copied into private storage, public copy deleted | yes | 2026-11-06 20:16:01 `imported config.json into private storage, deleted /sdcard copy` | |
| Signed CI build, install over SSH | GitHub Actions builds with the fixed key; `scp` + installer | yes | 2026-11-11 22:47:47 `process started, version 0.4.17` | Owner unlocks and taps Install |

## Telegram bot (Phase 3)

| Feature | How it works (1 line) | Tested on phone? | Evidence | Notes |
|---|---|---|---|---|
| Long polling | `getUpdates` every 50 s over HTTPS; no open port | yes | Every `telegram: command …` line | |
| Owner-only answers | Only `telegramUserId` gets replies | partly | Owner's commands answered | Never tried from another account (Phase 6) |
| Alert photo on motion | Photo sent on motion, 120 s cooldown, if `/alerts on` | yes | Owner received alert photos (Telegram, 2026-11-06) | Successful sends aren't logged, only retries |
| Alert video | The clip is sent too, if `/videos on` | yes | 2026-11-11 18:27:22 `[tg-media] telegram: sent video on try 2` | |
| `/start`, `/help` | Command list | yes | 2026-11-06 23:35:02 `telegram: command '/help'` | |
| `/snapshot` | Photo now | yes | 2026-11-06 23:14:29 `telegram: command '/snapshot'` + photo in Telegram | |
| `/clip N` | Record N s (1–30) and send it | yes | 2026-11-06 23:35:46 `telegram: command '/clip 5'` + video in Telegram | |
| `/last` | Sends the newest saved clip | yes | 2026-11-07 00:25:23 `telegram: command '/last'` | |
| `/status` | Uptime, battery, temperatures, storage, switches, RAM, Wi-Fi, camera | yes | 2026-11-11 22:30:53 `telegram: command '/status'` + reply | Temperatures shown since 0.4.16 |
| `/record`, `/alerts`, `/videos` on/off | Switches saved in private prefs | yes | 2026-11-11 17:51:01 `/alerts on`, `/videos on`, `/record on` | |
| `/log` | Last 30 log lines | yes | 2026-11-06 23:46:09 `telegram: command '/log'` | |
| `/reload` | Re-reads settings | yes | 2026-11-06 23:47:10 `telegram: command '/reload'` | |
| `/sshd` | Asks Termux to start sshd | no | — | Never sent; same code path as the boot start (yes) |
| Back-online message | "🟢 CCTV back online (vX) Why: …" on every start | yes | 2026-11-10 10:55:18 `started: phone rebooted (first signal: WIFI_STATE_CHANGED, 0 min after boot)` + 10:55:28 `sent message on try 3` | |
| Battery warnings | Low voltage (< 3.6 V), unplugged, back on charger | partly | Drain test 2026-11-09 20:00 → 2026-11-10 01:07 (`3.18V`) | Successful sends aren't logged; confirm from Telegram |

## Reliability (Phase 4)

| Feature | How it works (1 line) | Tested on phone? | Evidence | Notes |
|---|---|---|---|---|
| Start after reboot | `BootReceiver` on Wi-Fi/network/charger/unlock/storage events (`BOOT_COMPLETED` is blocked) | yes | 2026-11-07 01:12:21 and 2026-11-10 10:55:18 `started: phone rebooted (first signal: WIFI_STATE_CHANGED, 0 min after boot)` | Reboot with a screen PIN: test pending (docs/STATUS.md) |
| Restart after an update | `MY_PACKAGE_REPLACED` | yes | 2026-11-07 01:09:40 `receiver: android.intent.action.MY_PACKAGE_REPLACED (uptime 20 min) -> starting service` | |
| 1-minute watchdog | AlarmManager restarts the service if it isn't running | yes | 2026-11-07 00:45:10 `started: 1-minute check found the service stopped` | START_STICKY doesn't work here (S6) |
| `/teststop` | Stops the service to test the watchdog | yes | 2026-11-07 00:44:21 `teststop: stopping service` → 00:45:10 restarted | Screen on and off |
| `/testkill` | Kills the process to test recovery | yes | 2026-11-07 00:33:13 `testkill: killing own process` → 00:37:52 `5-minute check found the service stopped` | Not re-run since the check became 1 minute |
| sshd at boot | First start per boot asks Termux (RUN_COMMAND) to run `start-sshd` | yes | 2026-11-10 10:55:17 `termux: asked to run start-sshd (first start since boot) -> sent` + SSH works | Becomes on-demand in Phase 7 |

## Safety and operations (Phase 5, Tasks 1–3)

| Feature | How it works (1 line) | Tested on phone? | Evidence | Notes |
|---|---|---|---|---|
| JVM unit tests | `core` tested with `scripts/jvmtest.sh` and in CI | n/a | `OK (27 tests)` | Not a phone feature |
| Battery heat levels + camera pause | HeatMonitor 40/45/50 °C, resume < 42; EMERGENCY stops the camera | yes | 2026-11-08 16:17:30 `heat: battery 51.0°C, pausing camera` → 16:19:51 `heat: battery cooled to 28.2°C, resuming camera` | Battery sensor follows the charger (S7) |
| CPU heat levels + camera pause | `sensor_arm`, 52/55/60 °C, resume < 55 | yes | 2026-11-11 22:49:36 `heat: cpu 61.0°C, pausing camera` + `camera stopped` → 22:51:48 `heat: cpu cooled to 41.017°C, resuming camera` + `camera started` | Config line: 2026-11-11 22:47:47 `cpu=52.0/55.0/60.0/55.0°C` |
| `/testtemp C`, `/testtemp cpu C` | Fakes a reading for 3 checks | yes | 2026-11-11 22:49:36 `testtemp: fake cpu 61.0°C (2 checks left)` | |
| Outage detection + offline summary | OutageTracker; "📴 Was offline HH:mm–HH:mm (N min), M motions while offline" | yes | 2026-11-11 17:51:01 `outage 14:09-17:51, 3 motions` (3 `motion:` lines in that window); 2026-11-09 18:25:20 `outage 18:22-18:25, 0 motions` (Telegram HTTP 502) | Outages under 60 s aren't reported |
| Retry until delivered | Every send retries (5 s → 60 s backoff) until sent; queues of 20, oldest dropped | partly | 2026-11-10 10:55:28 `sent message on try 3`; 2026-11-11 18:27:22 `sent video on try 2` | **Untested: I can't cut Wi-Fi.** An alert held through a long outage was never seen (alerts were off on Nov 11) |
| Two send lanes | `tg-text` and `tg-media` threads, so a stuck upload can't delay replies | partly | Both lanes log (`[tg-text]`, `[tg-media]`) | **Untested: I can't cut Wi-Fi.** Not seen under a stuck upload (S8) |
| Outage cause | After 2 min offline: Wi-Fi state + a `generate_204` check page → Wi-Fi down / login page / no internet / Telegram only; added to the offline summary | no | — | Task 4 (0.4.x); waits for a natural outage, or the phone's Wi-Fi switched off for 3+ min |
| IP and network name in `/status` and back-online | `Wi-Fi: <network>, IP <address>, -77 dBm (3/4)` | no | — | Task 4; phone check pending |
| Late commands | After > 2 min: settings and read-only commands run, `/snapshot`, `/clip`, `/sshd`, tests skipped with a reply | no | — | **Untested: I can't cut Wi-Fi.** Unit-tested (`LateCommandsTest`); the 2026-11-08 `skipped old command` lines are the old bug (S9) |

Accepted risk for the untested paths: docs/DECISIONS.md (Task 3 close) and docs/INCIDENTS.md S15.
