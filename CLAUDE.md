# CLAUDE.md: phone-cctv

Android 5.1 phone (LYF Flame LS-4005, ARMv7, ~470 MB RAM, low-RAM mode) used as a
motion camera with a Telegram bot. Built only on GitHub Actions; installed over SSH.

## Project rules (non-negotiable)
- Kotlin + Android framework APIs only. No AndroidX, no new libraries (test-only
  dependencies like JUnit/org.json for JVM tests are allowed).
- minSdk 21, targetSdk 22, armeabi-v7a. Don't break Android 5.1.
- Never log, print, commit, or put in a URL: the bot token, the keystore, its password.
- Ask before anything that uses the GitHub account (repo settings, secrets, releases,
  new repos). Pushing to main for the agreed CI build is allowed.
- One commit per finished task (tests pass, phone-verified where the task says so).
- Keep IP addresses, usernames, hostnames and SSH details out of every repo file,
  including docs/ and docs/plans/.
- Every feature is verified on the real phone; "done" needs phone log lines or a
  Telegram message as evidence.
- Pure logic lives in plain Kotlin classes with JVM unit tests; Android classes stay thin.

## How to work (adapted from forrestchang/andrej-karpathy-skills, MIT)
1. Think before coding: state assumptions; present options instead of choosing
   silently; ask when unclear.
2. Simplicity first: minimum code for the request; nothing speculative.
3. Surgical changes: touch only what the task needs; match the existing style;
   mention unrelated issues instead of fixing them.
4. Goal-driven: define the check first (test, phone log, Telegram message);
   loop until it passes.

## Useful facts
- Phone access: the `phone` alias in the local SSH config. Logs: /sdcard/cctv/log.txt.
- Deploy: scripts/deploy.sh (phone must be unlocked for the installer).
- Phone quirks: battery % is unreliable (use voltage); BOOT_COMPLETED never arrives
  (app starts on Wi-Fi events); START_STICKY doesn't restart (1-min alarm does);
  reconnect() after MediaRecorder hangs (always reopen the camera).
