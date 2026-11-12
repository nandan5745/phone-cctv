# Roadmap (approved 2026-11-08)

Copied from the approved planning document, without private details. Step-by-step tasks: `docs/plans/phases-5-10.md`. Current position: `docs/STATUS.md`.

## Phase status (2026-11-12)

| Phase | What | Status |
|---|---|---|
| 1 | Repo, CI build, fixed key, SSH install, camera service | ✅ done 2026-11-06 |
| 2 | Motion detection, clips, storage cap | ✅ done 2026-11-06 |
| 3 | Telegram bot, alerts, commands | ✅ done 2026-11-06 |
| 4 | Reliability: boot/update start, alarm watchdog, sshd at boot | ✅ done 2026-11-07 (7-day soak still to come) |
| 5 | Safety and operations | ✅ done 2026-11-12 (Task 3 partly untested; Task 4 outage cause untested until a real outage) |
| 6 | Roommates and roles | 🔄 Task 7 started 2026-11-11 |
| 7 | Security hardening, SSH on demand, SECURITY.md | ⏳ not started |
| 8 | Better motion detection (zones, background model, darkness, IR test) | ⏳ not started |
| 9 | Native phone features (`/speak`, tamper alert) | ⏳ not started |
| 10 | Tests, metrics, docs, 7-day soak, results | ⏳ not started |

## Owner decisions

Roommates via **private chats** (approved with `/allow`), SSH **on demand**, night = **IR remote test + low-light tuning**, timeline **2–4 weeks**. Phases 5–9 must be on the phone by the **end of week 3** so the 7-day soak in week 4 runs on final code.

Week 1 started 2026-11-08 (roadmap approval): end of week 3 = **2026-11-28**, soak ends **2026-12-05**.

| Week | Work |
|---|---|
| 1 | Phase 5 + Phase 6 |
| 2 | Phase 7 core + Phase 8 |
| 3 | Phase 10 (tests, metrics, fault runs, docs) + Phase 9 (`/speak`, tamper) |
| 4 | 7-day soak, RESULTS.md with real numbers, resume bullets |

**Cut for time:** group chat, `/siren`, `/torch`, certificate pinning, moving clips to private storage (documented as residual risks), TFLite person check (stretch only).

## Findings the roadmap is built on (2026-11-08 review)

- 29 h without a restart; the app came back by itself after a reboot.
- Battery is worn (under 1 h unplugged); its % reading is wrong; voltage is reliable.
- App heap flat at 1–2 MB: no leak.
- Night: too dark to see. Morning: 445 false triggers from a moving curtain and daylight.
- Home internet dropped for over an hour, and messages were lost after 8 tries.
- Details: `docs/PHONE.md`, `docs/INCIDENTS.md`.

## Vulnerabilities (ranked) and where they're handled

| # | Issue | Severity | Plan |
|---|---|---|---|
| 1 | Unpatched Android 5.1 (2016 patch): Bluetooth/Wi-Fi/media bugs | High (needs a nearby or network attacker) | Radios off, no other apps; app only parses Telegram JSON (Phase 7) |
| 2 | sshd always listening, old frozen OpenSSH | Medium on shared Wi-Fi | SSH on demand with auto-off (Phase 7) |
| 3 | `allow-external-apps`: any app with Termux's RUN_COMMAND permission can run code as Termux | Medium if another app is installed | Keep the phone app-free; documented (Phase 7) |
| 4 | Clips/photos/log on `/sdcard`, readable by any app or anyone holding the unlocked phone | Medium (privacy) | Screen PIN; private storage = future work. Not full-disk encryption (needs PIN before boot) |
| 5 | Alerts lost during outages | Medium | Fixed in Task 3 (retry until delivered) |
| 6 | Telegram isn't end-to-end encrypted | Inherent | Documented |
| 7 | Exported `BootReceiver` | Low: only `QUICKBOOT_POWERON` wasn't system-protected, and it could only start the CCTV | Removed (Task 6, 2026-11-11) |
| 8 | Command injection | None found (fixed script path, no shell for user text) | Keep it so; tests |
| 9 | Bot token theft | Low (private storage, never logged, never committed) | — |
| 10 | Bot spam | Low | Per-user rate limit (Phase 6) |

## Review of another AI's suggestions

| Suggestion | Verdict |
|---|---|
| Thermal states, cooldowns, recovery message | Accepted (Phase 5) |
| Battery current monitoring | Not possible here (not reported) |
| Multi-user allowlist + roles | Accepted, with an owner approval flow |
| Wi-Fi situations, captive portal | Accepted (Task 4) |
| Tailscale | Rejected: no Android 5 client |
| Termux:API | Rejected (needs Android 7); ideas done natively (Phase 9) |
| Speech-to-text, SMS, contacts, call log, location, clipboard | Rejected: no CCTV use, sensitive |
| 1.75 MB per clip | Measured: ~2.3 MB |
| Storage dashboard, metrics, fault injection, tests | Accepted (Phases 5, 10) |
| TFLite person detection | Stretch only, as on-trigger verification, if it fits |
| "Reliable and measurable before more features" | Strongly agreed |

## Phases 5–10 in brief

- **5 Safety/ops:** test setup; battery + CPU heat levels with camera pause; alerts survive outages, separate text lane, late-command rules, offline summary; network diagnostics + IP in `/status`; storage dashboard + daily summary; remove `QUICKBOOT_POWERON`, uninstall Termux:Boot.
- **6 Roommates:** `Auth` (owner/member, private chats only), `/allow`, `/revoke`, `/users`, owner notice for unknown users, per-user rate limit, test commands owner-only; `/code-review` high + `/security-review`.
- **7 Security:** `/sshd on [min]` / `off` with auto-off, no sshd at boot unless `sshdAtBoot`; radio status; phone checklist; `docs/SECURITY.md`; `/security-review`.
- **8 Motion:** `grilling` first; ignore zones (`/zones`, `/ignore`, `/watch`); background model; darkness notice + low-light tuning; IR remote test; false alarms/day measured.
- **9 Native features:** `/speak` (Pico TTS), accelerometer tamper alert.
- **10 Evidence:** `/simplify`; metrics CSV + alert latency + `/metrics`; remaining unit tests; fault-injection runbook; ARCHITECTURE/SECURITY/RESULTS; 7-day soak; `grilling` on RESULTS.md.

## Resume angle

Strong already: a 10-year-old, 470 MB-RAM Android 5.1 phone running unattended; hardware/OS quirks diagnosed and worked around (unreliable fuel gauge → voltage; blocked boot broadcast → event-based start; no START_STICKY → alarm watchdog; charger-following battery sensor → CPU sensor); no open ports; CI-signed builds installed without USB.

What makes it great: measured numbers (uptime, alert latency, recovery times, false alarms before/after zones, RAM/CPU/temperature), a threat model, tests in CI. Draft bullets (fill in only measured numbers):
- Built a self-healing surveillance system on a legacy Android 5.1 phone (ARMv7, 470 MB RAM) in Kotlin with no third-party libraries: motion detection, hardware H.264 recording, Telegram control over outbound-only HTTPS.
- Achieved __% uptime over __ days with automatic recovery from crashes (≤ __ s), reboots (≤ __ s) and network outages; worked around vendor firmware that blocks boot broadcasts.
- Cut false alarms by __% with masked zones and a background model; median alert latency __ s.
- Designed role-based multi-user access and a threat model; no listening ports, on-demand SSH.

## Verification rule

CI (unit tests + signed build) must pass; every feature is checked on the phone with quoted log lines or a Telegram message; a 7-day soak after Phases 5–9.
