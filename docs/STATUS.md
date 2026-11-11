# Status

**Updated:** 2026-11-11 · **Installed on the phone:** 0.4.16 (commit `47824b0`) · **Built, waiting to install:** 0.4.17 (commit `36ddda4`, CPU thresholds), APK already copied to `/sdcard/Download/cctv.apk`

## Current task

**Task 3** (open: phone re-test), plus the **Task 2 follow-up** phone check (CPU thresholds). Both need the owner. Task 4 starts after both. "The owner" = the person running this project (the user of the Claude session); they unlock the phone and use Telegram.

## Where we are

| Task (docs/plans/phases-5-10.md) | Status |
|---|---|
| 1 Test infrastructure | ✅ done 2026-11-08 |
| 2 Battery + CPU heat levels, heat pause | ✅ done 2026-11-08 · ⚠️ follow-up (CPU 52/55/60, resume 55) built, **phone check pending** |
| 3 Alerts survive outages, two send lanes, late commands, offline summary | 🔄 code done and installed · **phone re-test pending** (see below) |
| 4 Network diagnostics + IP in `/status` | ⏭ next |
| 5 Storage dashboard + daily summary | ⏳ |
| 6 Housekeeping (drop `QUICKBOOT_POWERON`, uninstall Termux:Boot) + Phase 5 review | ⏳ |
| 7–22 (Phases 6–10) | ⏳ |

Phases 1–4: done (see `docs/ROADMAP.md`).

## Exact next step

1. **Install 0.4.17** (phone unlocked → `ssh phone 'am start -a android.intent.action.VIEW -t application/vnd.android.package-archive -d file:///sdcard/Download/cctv.apk'` → Install → Done).
2. **CPU threshold check:** log shows `config: … cpu=52.0/55.0/60.0/55.0°C`; owner sends `/testtemp cpu 61` → "🚨 Phone CPU 61.0 °C: camera stopped"; about 3 min later the camera comes back once the real reading is below 55. Quote `heat:` and `camera started` lines in the ledger.
3. **Task 3 re-test** (owner): `/alerts on`, `/record on`; cut the home internet; walk past; from mobile data send `/videos off` and `/snapshot`; keep the internet off **≥ 3 more minutes** (so they arrive > 2 min old); restore. Expected: delayed alert photo; "📴 Was offline …, ≥1 motions"; "Motion videos: OFF" + log `running late command '/videos off'`; "⏭ Skipped /snapshot …" + log `skipped old command`. Then `/videos on`. Close Task 3 with `task-done` (docs/WORKFLOW.md §1).
4. Then start **Task 4**.

## Waiting on the owner

- Unlock the phone for the 0.4.17 install; send `/testtemp cpu 61`.
- Task 3 re-test (internet cut).
- Was the 2026-11-10 10:55 reboot manual? (INCIDENTS O5)
- Phone settings to verify (docs/PHONE.md): Wi-Fi sleep "Always", screen PIN, Bluetooth/NFC/location/mobile data off.

## Open issues

See `docs/INCIDENTS.md`: O1 hours-long outages (cause unknown, Task 4), O2 Task 3 re-test, O3 curtain false triggers (Phase 8), O4 darkness (Task 15), O5 unexplained reboot.

## Current switches on the phone

Last commands: `/alerts on`, `/videos on`, `/record on` at 2026-11-11 17:51 (all ON).
