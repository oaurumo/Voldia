# Voldia

[中文](README.md) | English

Tap the floating ball to bring up the system volume panel anytime.

Voldia is a lightweight floating volume control tool for Android: a draggable floating ball stays on your screen — single tap to summon the system volume panel, long press to expand a volume card for direct media volume control. The card and the main screen also offer Shizuku-powered shutdown / reboot actions.

![minSdk](https://img.shields.io/badge/minSdk-23-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-37-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple)

## Features

- **Floating ball**
  - Single tap: summon the system volume panel
  - Long press: expand / collapse the volume card
  - Drag: reposition anywhere; auto-snaps to the screen edge on release
- **Volume card**
  - Drag the slider to adjust media volume with a live percentage readout
  - One-tap jump to the system volume panel
  - Drag the card header to move it; auto-collapses after 5 seconds of inactivity
  - Shutdown / reboot buttons (require Shizuku authorization, with a confirmation dialog to prevent mis-taps)
- **Appearance customization**: ball size (dp), opacity (%), card width (%) — applied in real time and persisted
- **Quick Settings tile**: add "Voldia Volume" to the system Quick Settings panel (Android 7.0+)
- **Foreground service**: persistent notification with "Volume card" and "Stop" actions

## Requirements

- Android 6.0 (API 23) to Android 17 (API 37)
- "Display over other apps" (overlay) permission
- Shutdown / reboot requires [Shizuku](https://shizuku.rikka.app/) running (started via either ADB or Root), with Voldia authorized in Shizuku

> Note: the Shizuku library declares minSdk 24. This project overrides it via `tools:overrideLibrary` to stay compatible with API 23, but Shizuku-related features are unavailable on API 23 devices; everything else works normally.

## Usage

1. Open the app and tap "Grant" to allow the overlay permission
2. Tap "Start floating ball" — the ball appears at the screen edge
3. Single-tap the ball to summon the system volume panel; long-press to expand the volume card and adjust media volume
4. (Optional) With the Shizuku service running, use shutdown / reboot from the main screen or the volume card
5. Adjust ball size, opacity, and card width in the "Appearance" section

## Build

```bash
git clone <repo-url>
cd Voldia
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

```
app/src/main/java/com/oaurumo/voldia/
├── MainActivity.kt          # Main UI: permission, service toggle, appearance, shutdown/reboot
├── FloatWindowService.kt    # Foreground service: floating ball, volume card, confirm dialog
├── VolumeTileService.kt     # Quick Settings tile (Android 7.0+)
├── PowerController.kt       # Shizuku authorization & shell execution (svc power shutdown/reboot)
└── BallPrefs.kt             # Appearance persistence (SharedPreferences)
```

## Permissions

| Permission | Purpose |
|------------|---------|
| `SYSTEM_ALERT_WINDOW` | Show the floating ball and volume card |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the floating ball running in the background |
| `MODIFY_AUDIO_SETTINGS` | Adjust media volume |
| `POST_NOTIFICATIONS` | Foreground service notification on Android 13+ |

## Technical Notes

- Kotlin + ViewBinding + Material3
- Floating windows built on `WindowManager` (`TYPE_APPLICATION_OVERLAY`)
- Shizuku API 13.1.5 — `Shizuku.newProcess` is private, invoked via reflection to run shell commands
- Shutdown / reboot execute `svc power shutdown` / `svc power reboot` after a confirmation dialog
