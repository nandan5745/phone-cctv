# The phone: measured facts and quirks

Everything here was measured on the real phone (dates in brackets). Network addresses, user names and keys are deliberately not in this file.

## Hardware and OS

| Item | Value |
|---|---|
| Model | LYF Flame, LS-4005 (build `LYF_LS-4005_06_17`) |
| Chip | Spreadtrum SC9830, `armv7l` / `armeabi-v7a` (32-bit ARM) |
| Android | 5.1 (API 22), security patch 2016-10-05, no updates |
| Low-RAM mode | `ro.config.low_ram=true` [2026-11-08] |
| RAM | ~471 MB total; ~160–250 MB free with the app running; app heap 1–3 MB of 48 MB [log, Nov 7–11] |
| Storage | `/sdcard` 2.6 GB, ~1.9 GB free [2026-11-11]; internal and `/sdcard` share the space |
| USB | Charging only, no data: no `adb`; everything goes over SSH (Termux) |
| Hardware features | Back + front camera, flash LED, microphone, GPS, Bluetooth, Wi-Fi, GSM radio, touchscreen (`pm list features`) |

## Camera

| Item | Value |
|---|---|
| Cameras | 2; back camera = id 0 |
| Preview sizes | 1280x720, 864x480, 720x480, **640x480** (used), 640x360, 624x352, 480x640, 352x288, 320x240, 240x320, 176x144 |
| Video sizes | Same as preview |
| FPS ranges | 5–15, 5–20, 5–30; ~7 fps delivered in normal room light |
| Focus | Fixed only |
| Sensor rotation | 90° (raw images are sideways; alert photos are rotated, clips carry an orientation hint) |
| Screen off | Frames keep coming into an off-screen `SurfaceTexture`; the 1-px overlay fallback was never needed |
| Encoder | Set to 700 kbps, actually ~920 kbps: a 20 s clip is ~2.3 MB; photo ~35–45 KB |
| MP4 layout | `moov` box at the **start** of the file, sizes written at stop; a cut-off file is detected by box sizes not adding up |
| Killed while recording | The system media process finalises the file, so the clip is still playable |
| After MediaRecorder | `camera.reconnect()` + `startPreview()` hangs ~6 s then fails; close + reopen takes ~0.3 s |
| Darkness | At night with a small lamp the picture is too dark: `max change` 0.0–0.6% all night |

## Sensors [2026-11-09]

| Device | What it is |
|---|---|
| `battery` thermal zone | Battery temperature, but it **follows the charger**, not heat (below) |
| `sensor_arm` thermal zone (`thermal_zone1`) | Processor temperature, reacts properly |
| `thermal-cpufreq-0` | Not a sensor: CPU slow-down when hot |
| `accelerometer` | Present (planned tamper alert, Phase 9) |
| `alps_pxy` | Probably proximity (maybe light); not declared to Android; not yet checked from an app |
| `msg2238_ts` | Touchscreen |
| `sprd-eic-keys`, `sprd-gpio-keys`, `headset-keyboard` | Buttons |

Only two temperature sensors exist. No gyroscope, compass or barometer.

## Battery and charging

| Fact | Evidence |
|---|---|
| **Percentage is unreliable** | Showed 100% "Full" at 3.31–3.38 V (nearly empty) on the charger [2026-11-06]; showed 80% at the moment it died [2026-11-07] |
| **Voltage is the honest signal** | Shutdown at ~3.15 V; app warns below 3.6 V |
| **Battery is worn** | Unplugged from full: died in under 1 hour with the camera running (3.96 → 3.73 → 3.30 → 3.15 V) [2026-11-07 01:35–02:32] |
| Second drain test | Unplugged at 4.15 V "100%" (2026-11-09 ~20:00), last log line 2026-11-10 01:07:58 at 3.18 V "80%": **~5 h** with the camera running (owner's test; much longer than the first, cause not known) |
| **Temperature follows the charger** | 25.7 °C during a 4.37 V charging pulse vs 28.6–30.1 °C at 4.03–4.15 V, jumps every minute; flat ~29 °C through night, day and direct sun [2026-11-08] |
| Charger type matters | Old adapter was detected as **USB (500 mA)** and the phone shut down while "charging"; new adapter + cable is detected as **AC** and holds 4.1–4.37 V [2026-11-06] |
| Charging current | Not reported by Android here (`BATTERY_PROPERTY_CURRENT_NOW` empty) |
| Plan | Keep it on AC 24/7; check the battery for swelling monthly |

## Processor temperature (`sensor_arm`)

| Condition | Reading |
|---|---|
| Normal, indoors | 37–50 °C; ~10% of minutes at ~50 °C; 51–54 °C only ~35 min in 3 days (3,861 minutes logged Nov 8–11) |
| Direct sun | 61.5–63 °C, already "very hot" [2026-11-09 14:20, owner's test] |
| Right after boot | 63.8 °C, back to ~47 °C within a minute [2026-11-11 23:37] (INCIDENTS S17) |
| App thresholds | warn 52, critical 55, emergency 60 (camera paused), resume below 55 |

## Wi-Fi and network

| Fact | Detail |
|---|---|
| Signal at the home spot | About −77 dBm ("3/4" bars): weak; uploads can stall |
| Ignores ping/ARP when idle | A scan from the Mac can miss the phone while its Telegram connection is fine; retry SSH |
| Address changes | DHCP gave it a new address after a reboot; the local SSH alias uses a fixed host-key alias so only the address needs editing |
| Outages | Several hours-long ones with DNS failing (cause unknown, see INCIDENTS.md); short ones that are Telegram HTTP 502 |
| Telegram HTTPS | Works on Android 5 (Go Daddy root CA is trusted) |

## Android behaviour on this phone

| Quirk | Workaround |
|---|---|
| `BOOT_COMPLETED` is never delivered to installed apps (also blocks Termux:Boot) | Start on `WIFI_STATE_CHANGED` etc. (arrives 0 min after boot) |
| START_STICKY doesn't restart a killed service | 1-minute AlarmManager check |
| `MY_PACKAGE_REPLACED` works | Restarts after every update |
| Android settings can't be read from Termux ("Error while accessing settings provider") | Check settings on the phone itself |
| Package installer: tapping **Open** instead of **Done** after an update | App starts as `started: app opened by hand` instead of `app updated`; both fine |

## Termux

| Fact | Detail |
|---|---|
| Build | Legacy GitHub build for Android 5 (`apt-android-5`), packages frozen |
| SSH | `sshd`, key login only; started at boot by the app via RUN_COMMAND |
| `allow-external-apps = true` | Set in `~/.termux/termux.properties` (owner approved) so the app can run `start-sshd` |
| `~/.termux/boot/start-sshd` | `termux-wake-lock` + `sshd` (run by the app) |
| Termux:Boot | Uninstalled 2026-11-11 (Task 6). Side effect: Termux lost `/sdcard` write access until the next reboot (INCIDENTS S18) |
| Termux:API | **Impossible**: every release v0.43–v0.53 needs Android 7 (minSdk 24) |
| Text to speech | Pico TTS is installed (`com.svox.pico`), usable natively (Phase 9) |

## Phone settings

| Setting | Status |
|---|---|
| Unknown sources (install APKs) | On |
| Keep Wi-Fi on during sleep | **Always** (owner checked 2026-11-11) |
| Bluetooth | Off (owner checked 2026-11-11) |
| Location | Off (owner checked 2026-11-11) |
| NFC | Not present on this phone |
| Screen lock PIN | **Set** 2026-11-11, secure start-up "No". Reboot left locked (23:37): `receiver: android.net.wifi.WIFI_STATE_CHANGED (uptime 0 min) -> starting service`, `termux: asked to run start-sshd`, `started: phone rebooted (first signal: WIFI_STATE_CHANGED, 0 min after boot)`, `camera started 640x480`, `sent message on try 3`; SSH worked |
| Mobile data | To verify (Task 11) |
| Unused preinstalled apps disabled | To do |
