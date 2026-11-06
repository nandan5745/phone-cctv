# phone-cctv

A small Android app that turns an old Android 5.1 phone into a motion camera.
Plain Kotlin and Android framework APIs only (no AndroidX), built on GitHub Actions.

## Status

Phase 1: foreground service keeps the back camera running (screen on or off) and
saves `/sdcard/cctv/test.jpg` every 10 seconds. Logs go to `/sdcard/cctv/log.txt`.

## Build

Every push to `main` builds a signed APK (artifact `cctv-apk`). Signing needs two repo secrets:

| Secret | Value |
|---|---|
| `CCTV_KEYSTORE_BASE64` | the keystore file, base64 encoded |
| `CCTV_KEYSTORE_PASSWORD` | its password (key alias is `cctv`) |

Keep a backup of the keystore. Losing it means the next APK won't install over the old
app (you'd have to uninstall first).

## Install on the phone (no USB)

```sh
scripts/deploy.sh   # downloads the latest APK, copies it over SSH, opens the installer
```
Then tap **Install** on the phone, and open the **CCTV** app once to start the service.

## Debug

```sh
ssh phone 'tail -f /sdcard/cctv/log.txt'
ssh phone 'ls -l /sdcard/cctv/'
scp phone:/sdcard/cctv/test.jpg .
```
