# phone-cctv

A small Android app that turns an old Android 5.1 phone into a motion camera.
Plain Kotlin and Android framework APIs only (no AndroidX), built on GitHub Actions.

## Status

- Phase 1 (done): foreground service keeps the back camera running with the screen off.
- Phase 2: motion detection, a 20 s clip per motion event in `/sdcard/cctv/clips/`
  (`YYYY-MM-DD_HH-MM-SS.mp4` plus a `.jpg` of the moment motion was seen), and a size cap
  (oldest deleted first). `/sdcard/cctv/test.jpg` is refreshed every 60 s to check the view.

## Settings

Optional. Put `/sdcard/cctv/config.json` on the phone, unlock it and open the CCTV app (that
reloads settings). The app moves the file into its private storage and deletes the `/sdcard` copy.
Missing keys use these defaults:

```json
{
  "motionPixelDiff": 25,
  "motionThreshold": 0.02,
  "motionFrames": 2,
  "analyzeFps": 2,
  "clipSeconds": 20,
  "storageCapMB": 1200,
  "minFreeMB": 300
}
```

Every 10 minutes the log prints a `status:` line with the biggest change seen
(`max change`). In an empty room that number should stay well below `motionThreshold`.

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
Unlock the phone when the script asks (the installer only shows on an unlocked phone), then tap **Install**, and open the **CCTV** app once to start the service.

## Debug

```sh
ssh phone 'tail -f /sdcard/cctv/log.txt'
ssh phone 'ls -l /sdcard/cctv/'
scp phone:/sdcard/cctv/test.jpg .
```
