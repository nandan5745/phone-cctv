# How to work on this project

## 0. Local setup on the Mac (not in the repo)

| Thing | What it is | If missing |
|---|---|---|
| SSH alias `phone` in `~/.ssh/config` | `Host phone` with `HostName <phone's address>`, `Port <Termux sshd port>`, `User <Termux user>`, `HostKeyAlias phone-cctv` | Get the values from the owner (Termux: `whoami`, `ifconfig`); the host-key alias keeps the saved key valid when DHCP changes the address |
| `~/.config/phone-cctv/private-patterns.txt` | One regex per line of private values (address prefix, Termux user, SSH port, bot username, email, key folder, phone MAC, Telegram user IDs of owner and roommates) for `scripts/privacy-check.sh` | Recreate from the same list; `chmod 600` |
| Ledger `.superpowers/sdd/phases-5-10/progress.md` | Execution log: task completions, BASE commits, rulings (git-ignored) | Rulings are also in docs/DECISIONS.md; BASE = the commit before a task's first commit (`git log`) |
| Local memory | Claude Code auto-memory for this project (`~/.claude/projects/<project>/memory/`): owner profile, preferences, private references | Not required to work; docs/ are the source of truth |
| `kotlinc` | Homebrew `kotlin` | `brew install kotlin` (ask the owner; ~470 MB) |
| superpowers plugin | Provides task-start/task-done | Version folder may differ: `ls ~/.claude/plugins/cache/claude-plugins-official/superpowers/` |

## 1. One task, start to finish

1. Read `docs/STATUS.md` (exact next step) and the task in `docs/plans/phases-5-10.md`.
2. Start it in the ledger:
   `~/.claude/plugins/cache/claude-plugins-official/superpowers/7.0.0/skills/executing-plans/scripts/task-start docs/plans/phases-5-10.md <N>`
   (prints the brief and the BASE commit; the ledger is `.superpowers/sdd/phases-5-10/progress.md`, git-ignored).
3. Pure logic: write the failing test in `android/app/src/test/java/com/nandan/cctv/core/`, run `scripts/jvmtest.sh` (RED), implement in `…/main/java/com/nandan/cctv/core/`, run again (GREEN).
4. Android glue: keep it thin; it calls the `core` classes.
5. Commit + push → CI tests and builds the APK.
6. Deploy (section 3), do the phone check, quote the log lines or Telegram message in the ledger.
7. Finish: `…/executing-plans/scripts/task-done docs/plans/phases-5-10.md <N> <BASE> -- scripts/jvmtest.sh`.
8. Same commit or the next: tick the plan steps, update `docs/STATUS.md`, `docs/FEATURES.md` (row + evidence for every feature added or tested), and any of PHONE / DECISIONS / INCIDENTS / ARCHITECTURE that changed, plus the local memory files.

Every deviation from the plan is a `Ruling:` line in the ledger and a row in `docs/DECISIONS.md`.

## 2. Tests

| Where | Command | Notes |
|---|---|---|
| Mac | `scripts/jvmtest.sh` | Compiles `core/*.kt` + tests with `kotlinc` (Homebrew); jars cached in `~/.cache/cctv-test/`; prints `OK (N tests)` |
| CI | push to `main` | `gradle testReleaseUnitTest assembleRelease`; a failing test means no APK |

## 3. Build and deploy

```sh
scripts/deploy.sh          # newest successful CI APK → phone → asks you to unlock → installer
```
Manual equivalent:
```sh
gh run list --limit 3 --json databaseId,number,headSha   # databaseId = <run-id>; number = APK version 0.4.<number>
gh run download <run-id> --name cctv-apk --dir .deploy
scp .deploy/cctv.apk phone:/sdcard/Download/cctv.apk
ssh phone 'am start -a android.intent.action.VIEW -t application/vnd.android.package-archive -d file:///sdcard/Download/cctv.apk'
```
**SSH is closed by default** (Task 10): first send `/sshd on 30` in Telegram (it closes by itself after 30 min, `/sshd off` sooner). For a long session at home, `sshdAtBoot: true` in the config brings back sshd at boot.

On the phone: unlocked → **Install** → **Done**. The update restarts the app by itself (`started: app updated`). If `ssh`/`scp` times out once, retry (the phone's Wi-Fi ignores the first packets when idle).

## 4. Reading the log over SSH

```sh
ssh phone 'tail -f /sdcard/cctv/log.txt'
ssh phone 'grep -E "process started|started:|receiver:" /sdcard/cctv/log.txt | tail'      # starts and why
ssh phone 'grep "telegram: command" /sdcard/cctv/log.txt | tail'                         # commands
ssh phone 'grep -E "poll failed \(1\)|reachable again|outage " /sdcard/cctv/log.txt'     # outages
ssh phone 'grep -E "running late|skipped old|on try|queue full" /sdcard/cctv/log.txt'    # Task 3 paths
ssh phone 'grep -E "motion:|clip saved" /sdcard/cctv/log.txt | tail'                     # motion
ssh phone 'grep "battery:" /sdcard/cctv/log.txt | tail -5'                               # battery + CPU
ssh phone 'grep -E "heat:|testtemp" /sdcard/cctv/log.txt | tail'                         # heat pause
ssh phone 'grep -E "CRASH|E \[" /sdcard/cctv/log.txt | tail'                             # errors
```
Older lines are in `/sdcard/cctv/log.1.txt`. Read-only phone facts: `/sys/class/power_supply/{battery,ac,usb}/`, `/sys/class/thermal/thermal_zone*/{type,temp}`.

## 5. Settings

Write the whole JSON (the import replaces the private config, so include the Telegram keys), then send **`/reload` from Telegram**:
```sh
ssh phone 'cat > /sdcard/cctv/config.json' < my-config.json
# then, in Telegram: /reload
```
Since 2026-11-12 opening the app (or any start not from Telegram) does **not** import a `/sdcard` config once a token is set; it deletes it and warns the owner (docs/SECURITY.md).

Telegram token + owner ID, **first setup only**: `scripts/set-telegram.sh` (asks for the token hidden, finds your ID from a `/start` message, writes it to the phone). If the token ever dies: clear the app's data or reinstall, then run the script again (docs/SECURITY.md). Keys and defaults: `docs/ARCHITECTURE.md` §6.

## 6. Skills per phase

| When | Skill |
|---|---|
| Every pure-logic change | `superpowers:test-driven-development` |
| Before saying "done" | `superpowers:verification-before-completion` (phone log or Telegram evidence) |
| End of each phase | `/code-review` medium (high for Phases 6 and 7) — **never ultra** |
| After Phase 6 and Phase 7 | `/security-review`, checked against `docs/SECURITY.md` (created in Task 9) |
| Before Phase 10 tests | `/simplify` on `CameraService.kt` |
| Before Phase 8 and at the end (RESULTS.md) | `grilling` |
| Anything fails on the phone | `superpowers:systematic-debugging` |

## 7. What always needs the owner

- Unlocking the phone for every install.
- Walking past the camera; covering it; the IR remote test.
- A second Telegram account (Phase 6 tests).
- Outage tests: the owner can't cut the Wi-Fi/internet (2026-11-11); outage paths are checked on natural outages.
- Anything using the GitHub account beyond pushing to `main` (secrets, settings, releases): ask first.
- Physical checks (battery swelling, phone settings).

## 8. Third-party skills and tools

Read every file first or scan with NVIDIA SkillSpector (ask before installing it), then explain what it does before installing. Built-in `grilling`, `/security-review`, `/code-review`, `/simplify` and `superpowers:*` cover the needs. Never add ECC, gstack or other agent frameworks.

## 9. Never in the repo

Bot token, keystore, keystore password or path, IP addresses, the Termux user name, the SSH port, the bot's username, personal email, Telegram user IDs and roommates' names (write "roommate, id hidden"). Check before committing:
```sh
scripts/privacy-check.sh     # greps tracked files for the patterns in ~/.config/phone-cctv/private-patterns.txt
```
The patterns file is local only (it lists the private values themselves), so it is never committed.
