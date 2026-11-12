# Status

**Updated:** 2026-11-12 · **Installed on the phone:** 0.4.31 (commit `bdf901a`, roommates with roles)

## Current task

**Task 9** (docs/SECURITY.md, `/code-review high` on Phase 6, `/security-review`). Tasks 7–8 done. Phase 5 (Tasks 1–6) is closed; Task 3 is closed **partly untested** (below); Task 4's outage-cause line is untested until a real outage. "The owner" = the person running this project (the user of the Claude session); they unlock the phone and use Telegram.

## Where we are

| Task (docs/plans/phases-5-10.md) | Status |
|---|---|
| 1 Test infrastructure | ✅ done 2026-11-08 |
| 2 Battery + CPU heat levels, heat pause | ✅ done 2026-11-08 · follow-up (CPU 52/55/60, resume 55) ✅ phone-checked 2026-11-11 |
| 3 Alerts survive outages, two send lanes, late commands, offline summary | ✅ done 2026-11-11, **partly untested** (accepted risk) |
| 4 Network diagnostics + IP in `/status` | ✅ done 2026-11-11 (IP phone-checked; outage cause untested until a real outage) |
| 5 Storage dashboard + daily summary | ✅ done 2026-11-12 (unprompted summary 09:00:15) |
| 6 Housekeeping (drop `QUICKBOOT_POWERON`, uninstall Termux:Boot) + Phase 5 review | ✅ done 2026-11-11 (review fixes 1–6; PIN reboot passed) |
| 7 Auth, Commands, RateLimiter (pure) | ✅ done 2026-11-11 |
| 8 Role-based routing in the bot | ✅ done 2026-11-12 (roommate test passed; member currently revoked) |
| 9 Phase 6 review + SECURITY.md | 🔄 current |
| 10–22 | ⏳ |

Phases 1–4: done (see `docs/ROADMAP.md`). Per-feature test status: `docs/FEATURES.md`.

## Task 3: what is and isn't proven (accepted risk, owner 2026-11-11)

The owner can't cut the Wi-Fi, so the planned outage re-test can't be run.
- **Seen on the phone:** the Telegram HTTP 502 blip on 2026-11-09 (`outage 18:22-18:25, 0 motions`); the 2026-11-11 outage (`outage 14:09-17:51, 3 motions`, matching 3 `motion:` lines); short retries (`sent message on try 3`, `sent video on try 2`).
- **Not proven on the phone:** an alert queued through a long outage and delivered after it (retry until delivered); late settings applied and late actions skipped with a reply (LateCommands); the two lanes under a stuck upload.
- These paths are unit-tested; the next natural outage is their phone test (look for `running late command`, `skipped old command`, `on try N` with large N). Details: docs/INCIDENTS.md S15, docs/DECISIONS.md.

## Exact next step

1. Phase 5 closed 2026-11-12.
2. **Task 9:** write `docs/SECURITY.md`; `/code-review high` on the Phase 6 diff (`9e76670..HEAD`); `/security-review`; each finding fixed (test + commit) or listed as an accepted risk. Then Phase 7 (Task 10).

## Waiting on the owner

- Roommate is revoked after the test; `/allow <id>` again when wanted (id: ask the owner; never in the repo).
- Mobile data off: still to verify (docs/PHONE.md).
- On the next real outage: nothing to do, but the log will show Task 3's and Task 4's untested lines (`running late command`, `net: outage cause:`).

## Open issues

See `docs/INCIDENTS.md`: O1 hours-long outages (cause unknown, Task 4), O3 curtain false triggers (Phase 8), O4 darkness (Task 15).

## Current switches on the phone

Last commands: `/alerts on`, `/videos on`, `/record on` at 2026-11-11 17:51 (all ON).
