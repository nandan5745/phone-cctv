# Incidents and bugs

Each entry: symptom, evidence, root cause, fix, commit, status. Newest open ones first.

## Open

### O1. Hours-long internet outages (cause unknown)
- **Symptom:** no replies or alerts for hours.
- **Evidence (log):** `poll failed (1)` … `reachable again after N failed tries`: Nov 7 17:11–18:27; Nov 8 20:18–22:59 and 23:48–03:29; **Nov 11 14:09–17:51 (3 h 42 min, 3 motions)**. Errors are `UnknownHostException` (DNS) and `SSLException … Connection timed out`.
- **Not the same as:** Nov 9 18:22–18:25 and Nov 11 06:44–06:47 were `HTTP 502 Bad Gateway` from Telegram's own servers.
- **Root cause:** unknown: home internet/router or the phone's Wi-Fi.
- **Next:** Task 4 records Wi-Fi state and a captive-portal/internet probe during outages.

### O3. Curtain and daylight false triggers
- **Evidence:** 445 motion events 2026-11-08 06:40–09:16, ~28 per 10 min, photos show a moving curtain and changing window light.
- **Next:** ignore zones + background model (Phase 8, Tasks 13–14).

### O4. Can't see in the dark
- **Evidence:** night `status:` lines show `max change 0.0–0.6%`.
- **Next:** darkness notice, low-light tuning, IR remote test (Task 15).

## Solved

### S1. Camera hangs after each clip
- **Symptom:** after MediaRecorder, `camera reconnect failed … startPreview failed` after ~6 s.
- **Root cause:** on this phone `reconnect()` + `startPreview()` on the same SurfaceTexture hangs.
- **Fix:** always close and reopen the camera (~0.3 s). Commit `e865535`. Solved 2026-11-06.

### S2. Detecting clips cut off by a crash or power loss
- **Finding:** this phone's MediaRecorder writes `moov` at the **start** of the file, so "has moov" isn't proof of a complete file.
- **Fix:** a clip is complete only if its top-level MP4 boxes add up exactly to the file size; broken `.mp4`, `.tmp` and 0-byte files are deleted at startup. Tested with a truncated copy. Commit `e865535`.

### S3. Phone shut down while "charging"
- **Symptom:** 2026-11-06 the phone died on the charger; restarted at 0%.
- **Evidence:** `/sys/class/power_supply`: battery `Full`, 3.31–3.38 V and falling, charger detected as **USB**.
- **Root cause:** weak/loose charging (USB-mode 500 mA) plus a fuel gauge that wrongly says "Full".
- **Fix:** new adapter + cable (now detected as AC, 4.1–4.37 V); app warns on low **voltage** (commit `263d71d`).

### S4. Battery percentage is meaningless
- **Evidence:** 100% at 3.3 V; 80% at the moment of shutdown.
- **Fix:** warnings use voltage (< 3.6 V). Commit `263d71d`.

### S5. App didn't start after reboot
- **Evidence:** after a reboot no `receiver:` line at all, and Termux:Boot didn't run either; `ro.config.low_ram=true`.
- **Root cause:** the phone never delivers `BOOT_COMPLETED` to installed apps. Most likely the vendor's low-RAM-mode filtering (`ro.config.low_ram=true`); the exact mechanism is unproven (no system log access).
- **Fix:** also listen for Wi-Fi/network/charger/unlock/storage events; first one starts the service. Commit `99fbf44`. Reboot test passed 2026-11-07 (`receiver: android.net.wifi.WIFI_STATE_CHANGED (uptime 0 min)`).

### S6. Crashed service didn't come back
- **Evidence:** `/testkill` at 00:33:13, nothing until the 5-min alarm at 00:37:52 (2026-11-07).
- **Root cause:** START_STICKY restart doesn't happen on this phone.
- **Fix:** AlarmManager check every 1 minute (commits `8037487`, `874e468`). `/teststop` came back within a minute, screen on and off.

### S7. Battery temperature doesn't show real heat
- **Evidence:** 25.7 °C at 4.37 V vs 28.6–30.1 °C at 4.03–4.15 V, switching every minute; flat ~29 °C in direct sun (2026-11-08).
- **Fix:** also watch the processor sensor `sensor_arm` (commit `25a6b67`); thresholds 52/55/60, resume 55 from measured data (commit `36ddda4`).

### S8. Replies 14 minutes late (2026-11-08 18:12)
- **Evidence:** commands received on time (`/snapshot` 18:12:46 … `/testtemp` 18:19:28); `sending photo failed (try 1..3): EOFException`; all replies arrived together ~18:26.
- **Root cause:** one send queue; a photo upload kept breaking on weak Wi-Fi and every text reply waited behind it.
- **Fix:** separate `tg-text` and `tg-media` lanes; every failed try logged with its duration. Commit `47824b0`. **Status:** fix shipped; not yet observed under another stuck upload (accepted risk, S15).

### S9. Settings commands silently ignored (2026-11-08 21:37)
- **Evidence:** outage 20:18–22:59; at 22:59 `skipped old command '/record off' (4808s old)` (also `/alerts off`, `/videos off`).
- **Root cause:** a rule skipped every command older than 2 minutes, without telling anyone.
- **Fix:** settings and read-only commands still run when late; "do it now" commands are skipped **with a reply**. Commit `47824b0`. Not yet seen on the phone (accepted risk, S15).

### S10. Alerts lost during outages
- **Evidence:** 22 `gave up sending` lines Nov 8 20:00 – Nov 9 04:10.
- **Fix:** every send retries until delivered (queues bounded at 20). Commit `47824b0`.

### S11. SSH stopped working after the phone got a new network address
- **Fix:** local SSH alias updated; a fixed host-key alias keeps the saved key valid when only the address changes (local config, not in the repo).

### S12. Mac Python couldn't verify HTTPS certificates
- **Evidence:** `CERTIFICATE_VERIFY_FAILED` from python.org Python; `curl` worked and showed the real Go Daddy certificate.
- **Fix:** `scripts/set-telegram.sh` uses `curl`, reading the URL from stdin so the token never shows in `ps`. Commit `c296544`.

### S13. Homebrew `kotlin` bigger than expected
- **Fact:** `brew install kotlin` also installed OpenJDK and graphics libraries (~470 MB, not ~100 MB). Remove with `brew uninstall kotlin openjdk` if needed. 2026-11-08.

### S14. Reboot 2026-11-10 10:55: owner's battery drain test, not a bug
- **What happened:** the owner unplugged the phone on purpose (2026-11-09 ~20:00, 4.15 V "100%") and let the battery run flat. Last line `2026-11-10 01:07:58 … battery: 80% DISCHARGING (unplugged) 28.5°C 3.18V`; plugged back in at 10:55.
- **Recovery (no help needed):** `10:55:16 process started, version 0.4.16` → `receiver: android.net.wifi.WIFI_STATE_CHANGED (uptime 0 min) -> starting service` → `termux: asked to run start-sshd (first start since boot) -> sent` → `started: phone rebooted (first signal: WIFI_STATE_CHANGED, 0 min after boot)` → `camera started 640x480` → `[tg-text] telegram: sent message on try 3` (back-online message, 10 s after DNS came up).
- **Status:** not a bug; also proves reboot recovery and text retry again.

### S15. Task 3 closed partly untested (accepted risk, 2026-11-11)
- **Seen on the phone:** Telegram HTTP 502 blip `2026-11-09 18:22:53 poll failed (1): … HTTP 502 Bad Gateway` → `18:25:20 reachable again after 4 failed tries` → `outage 18:22-18:25, 0 motions`; the 2026-11-11 outage `14:09:55 poll failed (1): SSLException … Connection timed out` → `17:51:01 reachable again after 225 failed tries` → `outage 14:09-17:51, 3 motions` (matches the 3 `motion:` lines at 14:46:05, 17:49:20, 17:50:11); short retries `sent message on try 3` (2026-11-10 10:55), `[tg-media] sent video on try 2` (2026-11-11 18:27).
- **Not seen:** an alert held through a long outage and delivered after it (alerts were OFF), a late settings command applied (`running late command`), a late action skipped with a reply (new-style `skipped old command` + "⏭ Skipped …"), a stuck upload not delaying text (two lanes, S8).
- **Why:** the owner can't cut the Wi-Fi for a test. Accepted risk (docs/DECISIONS.md); the paths are unit-tested (`OutageTrackerTest`, `LateCommandsTest`) and the next natural outage is the test. Commit `47824b0`.
