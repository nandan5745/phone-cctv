# CLAUDE.md: phone-cctv

Android 5.1 phone (LYF Flame LS-4005, ARMv7, ~470 MB RAM, low-RAM mode) used as a
motion camera with a Telegram bot. Built only on GitHub Actions; installed over SSH.

## Start here (reading order)
1. `docs/STATUS.md`: installed version, current task, the exact next step, what waits on the owner.
2. `docs/plans/phases-5-10.md`: the task list (ticked steps = done); the ledger is
   `.superpowers/sdd/phases-5-10/progress.md` (local, git-ignored).
3. `docs/WORKFLOW.md`: how to run a task: tests, deploy, logs, skills.
4. Then as needed: `docs/FEATURES.md`, `docs/ARCHITECTURE.md`, `docs/PHONE.md`, `docs/INCIDENTS.md`,
   `docs/DECISIONS.md`, `docs/ROADMAP.md`.

## Docs map
| File | Holds |
|---|---|
| docs/STATUS.md | Where we are right now |
| docs/ROADMAP.md | Approved Phases 5–10 plan and phase status |
| docs/plans/phases-5-10.md | Step-by-step tasks with tests and phone checks |
| docs/ARCHITECTURE.md | Pipeline, files, threads, lifecycle, config keys, commands, log prefixes, CI |
| docs/FEATURES.md | Every feature/command: how it works, tested on the phone or not, evidence |
| docs/PHONE.md | Measured phone facts and quirks |
| docs/DECISIONS.md | Every decision with reason and cost if wrong |
| docs/INCIDENTS.md | Bugs/incidents: evidence, cause, fix, status |
| docs/WORKFLOW.md | How to work: tests, deploy, logs, skills, owner-only steps |

## Project rules (non-negotiable)
- Kotlin + Android framework APIs only. No AndroidX, no new libraries (test-only
  dependencies like JUnit/org.json for JVM tests are allowed).
- minSdk 21, targetSdk 22, armeabi-v7a. Don't break Android 5.1.
- Never log, print, commit, or put in a URL: the bot token, the keystore, its password.
- Ask before anything that uses the GitHub account (repo settings, secrets, releases,
  new repos). Pushing to main for the agreed CI build is allowed.
- One commit per finished task (tests pass, phone-verified where the task says so).
- Keep IP addresses, usernames, hostnames, ports and SSH details out of every repo file,
  including docs/ and docs/plans/. Run `scripts/privacy-check.sh` before committing.
- Every feature is verified on the real phone; "done" needs phone log lines or a
  Telegram message as evidence.
- Pure logic lives in `core` (no android.* imports) with JVM unit tests; Android classes stay thin.
- Reviews: `/code-review` medium (high for Phases 6–7), never ultra.

## Keep docs current (same commit as the task)
- After every task: update docs/STATUS.md and tick the plan's steps.
- Feature added or tested on the phone → docs/FEATURES.md (row + evidence) ·
  new phone fact → docs/PHONE.md · decision or ledger ruling → docs/DECISIONS.md ·
  bug/incident → docs/INCIDENTS.md · design change → docs/ARCHITECTURE.md.
- Update the local memory files (project status) too.

## How to work (adapted from forrestchang/andrej-karpathy-skills, MIT)
1. Think before coding: state assumptions; present options instead of choosing
   silently; ask when unclear.
2. Simplicity first: minimum code for the request; nothing speculative.
3. Surgical changes: touch only what the task needs; match the existing style;
   mention unrelated issues instead of fixing them.
4. Goal-driven: define the check first (test, phone log, Telegram message);
   loop until it passes.
