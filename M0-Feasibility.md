# Milestone 0: orientation feasibility

**Historical result for versions through 0.2.0:** the original single-change proposal cannot cover all Android 11+ phones. A second, paired compatibility route worked on the tested Android 14 iQOO 9 SE. Version 0.2.0 used a built-in local ADB client through Wireless debugging. Version 0.3.0 replaces that route with an Accessibility orientation window; the ADB findings below remain as historical feasibility evidence.

## Version 0.3.0 overlay test (2026-09-26)

The ordinary application overlay permission added a transparent window on the Android 17 emulator but did not rotate a portrait locked test activity. An Accessibility overlay with `WindowManager.LayoutParams.screenOrientation = SCREEN_ORIENTATION_LANDSCAPE` rotated that activity to landscape. The production build then launched the same test activity through a library tile in landscape and returned to portrait after the session ended. The iQOO 9 SE rotated its display when the Accessibility overlay appeared, but its locked screen stopped the test activity; app-level behavior there still needs an unlocked test. The new implementation does not use ADB or mutate compatibility flags or global rotation settings. It observes display orientation, which is weaker evidence than the old ADB check of the target activity's configuration.

Android exposes `screenOrientation` and `TYPE_ACCESSIBILITY_OVERLAY` in [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams). The [Rotation Control open source project](https://github.com/Charles-3Ready/rotation-control) documents the same Accessibility overlay approach. Those references informed the spike; the device tests above establish what worked on the tested builds. A second emulator session with Wireless debugging disabled (`adb_wifi_enabled = 0`) also launched the portrait locked probe in landscape and returned to portrait on exit.

## Test setup

- Android 15 (API 35) Pixel Tablet emulator and Android 17 preview (API 37) phone emulator, both using ADB shell (the same shell privilege Shizuku provides when started through wireless debugging or ADB).
- A throwaway app whose launcher activity declares `android:screenOrientation="portrait"`; its `onResume()` logs `resources.configuration.orientation` (`1` portrait, `2` landscape).
- The test APK and signing key were created outside this repository; the probe was reused for standalone ADB regression tests.

## Observations

| Device | Shell changes before launching test app | App configuration |
| --- | --- | --- |
| Tablet, API 35 | `am compat enable 310816437 dev.landscapify.spike`; rotation locked to landscape; `wm set-ignore-orientation-request false` | Portrait (`1`) |
| Tablet, API 35 | Same compat override; `wm set-ignore-orientation-request true`; rotation locked to landscape | Landscape (`2`) |
| Tablet, API 35 | Compat override reset; ignore-orientation still true; rotation locked to landscape | Portrait (`1`) |
| Phone, API 37 preview | `wm user-rotation lock 1` (phone landscape); `wm set-ignore-orientation-request true`; no compat override | Portrait (`1`) |
| Phone, API 37 preview | Same global settings plus `am compat enable 310816437 dev.landscapify.spike` | Portrait (`1`), letterboxed inside a landscape display |
| Phone, API 37 preview | Same settings plus `am compat enable 174042936 dev.landscapify.spike` (`FORCE_RESIZE_APP`) | Portrait (`1`), still letterboxed |
| Phone, API 37 preview | Enable `265464455` (`OVERRIDE_ANY_ORIENTATION`) and `266124927` (`OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE`); no display-wide settings changed | Landscape (`2`) |
| vivo I2019 / iQOO 9 SE, Android 14 (API 34) | Same paired overrides; user rotation remained `lock 0`, ignore-orientation remained `false` | Landscape (`2`) for a portrait-locked probe |

`dumpsys activity top` confirmed the phone's global display was landscape (`2400 x 1080`, `ROTATION_90`) while the test activity's own bounds remained portrait (`810 x 1080`). The successful tablet path required changing display-wide settings in addition to the per-app flag, so it is not a clean per-app override.

The original `310816437` commands were accepted, yet the desired phone result did not occur. The paired `265464455` and `266124927` overrides did force the probe into reverse landscape on the tested phones. This rules out using a successful command exit code as proof that an app was forced into landscape; the older build checked the target's actual configuration. `FORCE_RESIZE_APP` addresses size compatibility, not a phone app's portrait request.

## Consequence for v1

The original `310816437` route alone cannot support **all Android 11+ phones**. Version 0.2.0 used the paired route when exposed and retained the older route as a fallback. That build could not guarantee every game or OEM build: a target could opt out, change activities, or render incorrectly despite reporting a landscape configuration. Its session restored each saved compatibility flag, and the older route also restored display-wide settings.

Version 0.2.0 contained the non-root local ADB variant with runtime verification, session cleanup, and clear unsupported states. The previous Shizuku build launched Subway Surf in a landscape activity on the iQOO 9 SE (2400 x 1080, rotation 270), but its menu artwork was visibly clipped with large blank areas. The 0.2.0 standalone build paired directly with Wireless debugging on that phone and launched Subway Surf in a landscape activity (2400 x 1080, rotation 270). Leaving the game cleared the recovery record and left user rotation `free` and `ignoreOrientationRequest false` unchanged. The Android 17 emulator passed a portrait-locked probe test, including normal session cleanup and recovery after force-stopping Landscapify. Other devices and real apps still need app-level verification.

For unsupported combinations, the app reports the limitation instead of claiming universal Android 11+ coverage. Root-level hooks would be a separate architecture and are outside this app.

The physical phone's compatibility overrides were reset, the probe uninstalled, and its original `lock 0` rotation and `ignoreOrientationRequest false` settings were unchanged after the earlier Shizuku test.

## Platform references

- [Android compatibility mode: `OVERRIDE_ANY_ORIENTATION_TO_USER`, `FORCE_RESIZE_APP`, and ADB commands](https://developer.android.com/guide/practices/device-compatibility-mode)
- [Android 16 large-screen behavior and `sw600dp` boundary](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Android 15 compatibility change IDs](https://developer.android.com/about/versions/15/reference/compat-framework-changes)
- [Android 14 paired orientation override IDs](https://developer.android.com/about/versions/14/reference/compat-framework-changes)
