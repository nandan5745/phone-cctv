# Security

Threat model for the phone CCTV, written in Task 9 (2026-11-12) and checked by `/code-review high` and `/security-review` after Phase 6. Updated after Phase 7 (SSH on demand). Private details (addresses, ports, user names, IDs) are deliberately not in this file.

## What we protect

| Asset | Why it matters |
|---|---|
| Photos and clips of the room | Privacy of the owner and roommates |
| Control of the camera (`/record off`, `/alerts off`) | An attacker could blind it before a break-in |
| The bot token | Whoever has it can read the bot's messages (incl. photos) and pretend to be the bot |
| SSH access to the phone (Termux) | Full access to the clips, log and config |
| The signing keystore | Whoever has it can ship an "update" that installs over the app |

## Who might attack

| Attacker | Can | Can't (normally) |
|---|---|---|
| Someone on Telegram who finds the bot | Send it messages | Use it: not owner or member |
| A roommate added as a member | Get alerts, `/snapshot`, `/clip`, `/last`, `/status` | Change settings, add users, `/sshd`, tests, `/log` |
| Another device on the same Wi-Fi (shared PG Wi-Fi) | Scan the phone, reach open ports | Reach the camera: the app opens no ports |
| Someone holding the phone | Use whatever is unlocked | Get past the screen PIN (set 2026-11-12) |
| Another app installed on the phone | Read `/sdcard`; send broadcasts | Read app-private files |
| Someone reading the public GitHub repo | Read code and docs | Find the token, keystore, addresses or IDs (kept out; `scripts/privacy-check.sh`) |

## Attack surfaces and protections

| Surface | Protection | Where |
|---|---|---|
| Telegram messages | Answered only for the owner and approved members, **private chats only**; groups and channels ignored; strangers get no reply (owner notified once a day per stranger, at most 5 notices an hour); 10 messages a minute per member | `core/Auth`, `core/Routing`, `core/RateLimiter`, `Bot.handle` |
| Owner-only commands | Members may use only `/start /help /snapshot /clip /last /status`; everything else (incl. new commands) is owner-only by default; members' `/status` hides the Wi-Fi line (network name, phone IP) | `core/Commands.MEMBER` |
| Corrupt or missing `users.json` | Falls back to owner-only, never "nobody" or "everybody"; written via a temp file + rename | `Auth.fromJson`, `Bot.saveUsers` |
| Message text | Parsed as plain text; never run in a shell; sent back with no `parse_mode` (no formatting injection) | `core/Commands`, `TelegramApi` |
| Config file on `/sdcard` | Imported only on first setup (no token yet) or the owner's `/reload` from Telegram; any other time it is deleted and the owner warned (an installed app could otherwise drop its own token + owner ID and open the app) | `core/ConfigImport`, `Config.load` |
| Bot token | Stored only in app-private `files/config.json`; the `/sdcard` copy is deleted after import; never logged (`toString` hides it; errors are sanitised, `errorStream` instead of `inputStream` because the latter's message contains the URL) | `Config`, `TelegramApi.safe` |
| Network | No listening port in the app; outgoing HTTPS to Telegram only (plus one plain-HTTP connectivity check during an outage) | `Bot`, `TelegramApi` |
| `BootReceiver` (exported) | Only system-protected broadcasts (the unprotected `QUICKBOOT_POWERON` was removed in Task 6); a fake broadcast could at most start the CCTV | `AndroidManifest.xml` |
| Termux `RUN_COMMAND` | The app runs one fixed script path; no user text reaches it | `Termux.kt` |
| sshd | Key-only login. **Today always on after boot**; Phase 7 makes it on-demand with auto-off | Termux, Task 10 |
| Clips on `/sdcard` | Screen PIN; no other apps installed | Phone settings |
| Updates | Signed with the fixed key held only in GitHub secrets | CI |
| Repo | Private values listed in a local patterns file; `scripts/privacy-check.sh` before every commit | `scripts/` |

## Scenarios

| Scenario | What happens |
|---|---|
| A stranger finds the bot and sends `/record off` | Ignored, no reply; the owner gets "👤 Unknown user …" once that day |
| A member sends `/record off` or `/allow` | "Only the owner can do that." (log `refused … (owner only)`) |
| A member adds the bot to a group and uses it there | Ignored (log `ignored message from …: chat type group`), also for the owner |
| Someone floods the bot | After 10 messages a minute their messages are ignored (one log line a minute); stranger ids are forgotten after expiry |
| A roommate moves out | `/revoke ID`: no more alerts or answers, including alerts already queued during an outage (recipients are read when an alert is sent) |
| The token leaks or dies | Revoke it in BotFather. The app no longer imports a new config on open once it has a token, so: **clear the app's data** (Settings → Apps → CCTV → Clear data) **or reinstall**, then run `scripts/set-telegram.sh` again (first setup) and `/allow` members again (their list is in app data) |
| Another app on the phone writes `config.json` and opens the CCTV app | Ignored and deleted; the owner gets "⚠️ A config.json appeared … deleted without importing it" |
| Someone scripts many throwaway accounts | At most 5 "Unknown user" notices an hour |
| The phone is stolen | Screen PIN; clips on `/sdcard` are readable with a PC only if USB data worked (it doesn't on this phone) |

## Residual risks (accepted)

| Risk | Why accepted |
|---|---|
| Android 5.1, security patch 2016: known Bluetooth/Wi-Fi/media bugs | No updates exist; Bluetooth and location off, no other apps; the app parses only Telegram JSON |
| Telegram is not end-to-end encrypted for bots | Telegram's servers can see photos; acceptable for this use |
| Clips readable by any app with storage permission | Phone kept app-free; moving clips to private storage is cut for time |
| No certificate pinning | Pinning would break the bot if Telegram changes certificate authority; cut for time |
| `allow-external-apps = true` in Termux | Any app with Termux's RUN_COMMAND permission could run code as Termux; the phone stays app-free |
| sshd always on (until Phase 7) | Key-only; becomes on-demand in Task 10 |
| A member's Telegram account is taken over | The attacker gets what a member gets (photos, `/status`); the owner revokes |

## Review log

| Date | Review | Result |
|---|---|---|
| 2026-11-11 | `/code-review` Phase 5 | 9 findings; 6 fixed (incl. one send blocking a lane forever), 2 cleanups deferred, 1 merged into a fix (docs/INCIDENTS.md S16–S17) |
| 2026-11-12 | `/code-review high` Phase 6 (Tasks 7–8) | 9 findings, all fixed test-first: owner exempt from the rate limit; one queue item per alert with recipients read at send time (revoked users get nothing queued); one `/clip` at a time; members' `/status` without IP; one bad `users.json` entry no longer drops all members; ≤ 5 stranger notices an hour; unknown member commands say "Unknown command"; routing computed once |
| 2026-11-12 | Security review: **a manual pass**, not the `/security-review` skill (the skill compares against `origin/HEAD`, which is missing here, and everything was already pushed, so its diff would be empty). Covered the Phase 6 diff and the whole attack surface above | 1 extra finding from Phase 1, fixed: any installed app could drop `/sdcard/cctv/config.json` (its own token + owner ID) and open the app to take over the bot → import only on first setup or `/reload` (INCIDENTS S19) |
