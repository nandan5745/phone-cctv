# Status

**Updated:** 2026-11-11 · **Installed on the phone:** 0.4.17 (commit `36ddda4`, CPU thresholds 52/55/60, resume 55)

## Current task

**Task 4** (network diagnostics + IP in `/status`). Tasks 1–3 are closed; Task 3 is closed **partly untested** (below). "The owner" = the person running this project (the user of the Claude session); they unlock the phone and use Telegram.

## Where we are

| Task (docs/plans/phases-5-10.md) | Status |
|---|---|
| 1 Test infrastructure | ✅ done 2026-11-08 |
| 2 Battery + CPU heat levels, heat pause | ✅ done 2026-11-08 · follow-up (CPU 52/55/60, resume 55) ✅ phone-checked 2026-11-11 |
| 3 Alerts survive outages, two send lanes, late commands, offline summary | ✅ done 2026-11-11, **partly untested** (accepted risk) |
| 4 Network diagnostics + IP in `/status` | 🔄 current |
| 5 Storage dashboard + daily summary | ⏳ |
| 6 Housekeeping (drop `QUICKBOOT_POWERON`, uninstall Termux:Boot) + Phase 5 review | ⏳ |
| 7–22 (Phases 6–10) | ⏳ |

Phases 1–4: done (see `docs/ROADMAP.md`). Per-feature test status: `docs/FEATURES.md`.

## Task 3: what is and isn't proven (accepted risk, owner 2026-11-11)

The owner can't cut the Wi-Fi, so the planned outage re-test can't be run.
- **Seen on the phone:** the Telegram HTTP 502 blip on 2026-11-09 (`outage 18:22-18:25, 0 motions`); the 2026-11-11 outage (`outage 14:09-17:51, 3 motions`, matching 3 `motion:` lines); short retries (`sent message on try 3`, `sent video on try 2`).
- **Not proven on the phone:** an alert queued through a long outage and delivered after it (retry until delivered); late settings applied and late actions skipped with a reply (LateCommands); the two lanes under a stuck upload.
- These paths are unit-tested; the next natural outage is their phone test (look for `running late command`, `skipped old command`, `on try N` with large N). Details: docs/INCIDENTS.md S15, docs/DECISIONS.md.

## Exact next step

1. Start **Task 4** (`task-start docs/plans/phases-5-10.md 4`, docs/WORKFLOW.md §1): TDD `core/NetDiag.kt`.
2. When the owner has set a screen PIN (secure start-up "No"): one reboot test without unlocking; quote the `started: phone rebooted (first signal: …)` line in docs/FEATURES.md and docs/PHONE.md.

## Waiting on the owner

- Screen PIN, then the reboot test above.
- Mobile data off: still to verify (docs/PHONE.md).

## Open issues

See `docs/INCIDENTS.md`: O1 hours-long outages (cause unknown, Task 4), O3 curtain false triggers (Phase 8), O4 darkness (Task 15).

## Current switches on the phone

Last commands: `/alerts on`, `/videos on`, `/record on` at 2026-11-11 17:51 (all ON).
