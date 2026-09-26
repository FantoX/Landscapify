# Landscapify

Landscapify opens selected Android apps in a temporary landscape session. It requires no Shizuku app, Developer options, Wireless debugging, computer, or root access.

## Requirements

- Android 11 or newer (`minSdk 30`). Results depend on the Android build and target app; universal forcing is not guaranteed.
- Enable **Landscapify** in Android **Accessibility** settings. Its service creates a transparent, untouchable orientation window only while a session is active. It does not read screen content or perform gestures.
- Grant **Usage Access**. The foreground service uses app transition events to end the session after you leave the selected app.

There is no internet permission, ADB client, pairing key, or external service in the current app.

## Build and use

Open this directory in Android Studio with Android SDK 35 and JDK 17, or run:

```powershell
./gradlew.bat :app:assembleDebug :app:lintDebug
```

Install [app-universal-debug.apk](app/build/outputs/apk/debug/app-universal-debug.apk). Open Landscapify, tap **Set up**, and enable its Accessibility service in system settings. Return to Landscapify and add apps. When first launching a selected app, grant Usage Access. Tap a tile to start a session. Long press to remove it. **Pause forcing** ends the current session and prevents new ones.

Landscapify removes the orientation window after you leave the selected app for about three seconds, tap **Stop session** in its notification, or pause forcing. Android removes the window if the app process dies. It does not change display rotation settings or per-app compatibility flags. Some apps can still remain portrait, letterbox, or lay out badly; a rotated display alone does not prove that every activity is usable. If the display does not rotate, Landscapify marks that app unsupported until you remove and add it again.

Before installing this update over version 0.2.0, end any active landscape session. A leftover `pending_restore` record from an interrupted older session blocks new sessions because the new app no longer has ADB privileges to restore that older version's compatibility changes. Reinstall version 0.2.0 temporarily to recover such a record, then update again. The tested iQOO had no pending record before upgrade.

## GitHub releases

Pushing a tag such as `v0.3.0` runs [the release workflow](.github/workflows/release.yml). It checks that the tag matches `versionName`, builds and verifies signed `arm64-v8a`, `armeabi-v7a`, `x86_64`, and universal APKs, then publishes them with `SHA256SUMS.txt`. The universal APK also includes x86 for older emulators. Because this app has only one small native library, the ABI splits are only slightly smaller than the universal APK.

The workflow reads four repository Actions secrets: `LANDSCAPIFY_SIGNING_KEY_BASE64`, `LANDSCAPIFY_KEYSTORE_PASSWORD`, `LANDSCAPIFY_KEY_ALIAS`, and `LANDSCAPIFY_KEY_PASSWORD`. The release signing key is stored outside this repository. **Back up the keystore and its credentials securely:** losing them prevents future APKs from updating existing release installs. The workflow verifies the certificate SHA-256 fingerprint `AC:12:10:55:5C:F7:E1:80:E8:5F:A0:D4:46:3D:8C:B8:DD:30:0D:E4:6A:B6:31:3C:F3:82:83:4C:43:66:9B:56` before publishing.

For each later release, increase `versionCode`, set `versionName` to the new version, commit and push those changes, then push the matching tag. For example:

```bash
git tag v0.3.0
git push origin v0.3.0
```

Release APKs use a different signing key from local debug APKs. Android cannot install a release APK over a debug-signed copy; uninstall the debug copy first, which erases its local library. Future release APKs signed with the same key update normally.

## Verification

On an Android 17 phone emulator, the new build launched a portrait locked test activity through Landscapify in a 2400 × 1080 landscape display with Wireless debugging off, then returned to portrait after leaving it. Force stopping Landscapify during a session also returned the display to portrait without changing `wm user-rotation` or `wm get-ignore-orientation-request`. An Accessibility overlay also rotated the display on the iQOO 9 SE (Android 14), but the phone was locked during that probe, so target activity behavior on that device is not yet confirmed. See [M0-Feasibility.md](M0-Feasibility.md) for the older ADB feasibility tests and the new overlay test boundary.
