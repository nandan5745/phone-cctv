# phone-cctv

Turns a 10-year-old Android 5.1 phone (ARMv7, ~470 MB RAM) into a self-healing motion camera
you control from Telegram. Plain Kotlin and Android framework APIs only (no AndroidX), built
and signed on GitHub Actions, installed over SSH (the phone's USB is charge-only).

**What it does:** watches the back camera with the screen off; on motion it saves a photo and a
20 s clip, sends a Telegram alert (and optionally the clip); answers commands like `/snapshot`,
`/clip 10`, `/status`; warns about heat, low battery voltage and charging problems; survives
reboots, updates, crashes and network outages. It opens no ports: only outgoing HTTPS to Telegram.

## Documentation

| Read | For |
|---|---|
| [docs/STATUS.md](docs/STATUS.md) | Where the project is right now and the next step |
| [docs/ROADMAP.md](docs/ROADMAP.md) | The plan for Phases 5–10 and phase status |
| [docs/FEATURES.md](docs/FEATURES.md) | Every feature and command, and whether it's tested on the phone |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How it works: pipeline, files, threads, config, commands, logs |
| [docs/PHONE.md](docs/PHONE.md) | Measured facts and quirks of this phone |
| [docs/INCIDENTS.md](docs/INCIDENTS.md) | Bugs and incidents, with evidence and fixes |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why things are the way they are |
| [docs/WORKFLOW.md](docs/WORKFLOW.md) | Tests, build, deploy, logs, settings |
| [docs/plans/phases-5-10.md](docs/plans/phases-5-10.md) | Step-by-step task list |

## Quick start

```sh
scripts/jvmtest.sh           # unit tests for the core logic (needs kotlinc)
git push                     # CI runs the tests and builds the signed APK
scripts/deploy.sh            # copy the APK to the phone and open the installer (unlock the phone)
scripts/set-telegram.sh      # one-time: bot token + your Telegram ID onto the phone
ssh phone 'tail -f /sdcard/cctv/log.txt'
```

Signing needs two repo secrets, `CCTV_KEYSTORE_BASE64` and `CCTV_KEYSTORE_PASSWORD` (key alias
`cctv`). Keep a backup of the keystore: without it, the next APK won't install over the old app.
