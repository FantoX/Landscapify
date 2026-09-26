# Landscapify — Development Requirements Document

> **Implementation update (2026-09-26):** Later requests to remove Shizuku and Wireless debugging supersede the privilege approach in this kickoff draft. The current implementation uses an Accessibility orientation window during each session and Usage Access to detect when the user leaves the selected app. See [README.md](README.md) for current setup, verification, and limits.

**Version:** 0.1 (draft for build kickoff)
**Platform:** Android
**One-liner:** A small "launcher" app where you pick installed apps into a library; tapping a library entry opens that app forced into landscape orientation, even if the app itself is built to run in portrait — without permanently changing that app's behavior when launched normally.

---

## 1. Read this first — the technical reality

This is the part that decides the whole architecture, so it comes before the feature list.

On stock, non-rooted Android, **a third-party app cannot change another app's screen orientation from the outside.** Orientation is owned by the target app itself: it's declared in that app's manifest (`android:screenOrientation="portrait"`, etc.) and enforced by the system's `WindowManagerService`. There is no public SDK call like `forceOrientation(otherApp, LANDSCAPE)`. This is intentional sandboxing, not a missing feature — if it were possible via public API, every screen-recording/kiosk app would already do it, and it would also break the same publisher's control over their own UI.

So "alter the app's height/width dynamically" needs one of three real-world techniques, in increasing order of power and setup cost:

| Tier | Technique | Works on apps that hard-lock orientation? | Setup burden on the user | Root needed? |
|---|---|---|---|---|
| **1 — Best-effort, SDK only** | Toggle system auto-rotate + `WRITE_SETTINGS` while the target app is foreground | No — only helps apps that already declare `unspecified`/`sensor`/`fullSensor` orientation | None | No |
| **2 — Shizuku (recommended)** | Use Shizuku's ADB-shell-level privilege to invoke the same per-app orientation/aspect-ratio compatibility override that Android itself exposes as a manual Settings toggle on large-screen devices (Android 14+ "user aspect ratio override" framework) | **Yes** — this override is specifically designed to force orientation regardless of the app's manifest | One-time Shizuku pairing (via wireless debugging or a computer running ADB once) | No |
| **3 — Root / Xposed (LSPosed module)** | Hook `WindowManagerService`/`ActivityRecord` directly | Yes, unconditionally, on any Android version | Rooted device + LSPosed | Yes |

**Recommendation for v1: build Tier 2 only.** It is the only option that is both (a) capable of actually forcing orientation on locked-down apps and (b) achievable without asking the user to root their phone. Tier 1 is documented here for completeness but should be left out of the initial build — supporting it means building and maintaining two completely different enforcement code paths for a feature that only half-works. Tier 3 is a possible "Pro" add-on later for rooted users, not v1 scope.

**This means Shizuku is a hard dependency for Landscapify v1**, not an optional enhancement. The app should say so plainly during onboarding rather than pretending it's a plain download-and-go utility.

### Open technical risk (flag this before writing feature code)

The exact hidden API / shell command used to set the per-package orientation override (`am compat enable <CHANGE_ID> <package>` is the general shape of it, based on how Android's own large-screen compatibility framework works) needs to be **confirmed with a throwaway spike** against the actual Android versions you're targeting, because:
- The specific compat change-ID is not officially public API and has shifted between Android 14/15/16 point releases.
- OEM skins (One UI, MIUI/HyperOS, ColorOS) sometimes patch or restrict this part of AOSP.
- Behavior may differ for apps that ignore the override entirely (a small number of apps set a manifest property that opts them out of the user override — `PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE = false`). For those, Landscapify simply cannot force them, on any tier, without root. This should surface in the UI as a "this app can't be forced" state rather than silently failing.

Do this spike as Milestone 0, before any UI work — it determines whether the rest of the plan is even buildable as described.

---

## 2. Scope for v1

### In scope
- A **Library** screen: grid of apps the user has added, each shown with its real icon and name.
- An **add-app picker**: a searchable list of the user's installed, launchable apps, with multi-select "add to library."
- **Remove from library** (long-press or swipe).
- **Tap a library entry** → Landscapify launches that app **and** activates a landscape-forced session for it.
- **Session-scoped enforcement**: the orientation override is applied only while the app was opened through Landscapify, and is automatically reverted once the user leaves that app — so opening the same app later via its own icon behaves completely normally (unmodified portrait, as designed). This matches the "only if opened via my app" requirement directly; it's what makes this a routing app rather than a permanent system tweak.
- **Shizuku status indicator**: a persistent small banner/badge showing connected/not-connected, with a one-tap link into Shizuku's pairing flow if it isn't set up yet.
- A single global settings toggle to pause/disable the whole forcing behavior without removing the library.
- Reasonable empty states ("Your library is empty — add an app to get started").

### Explicitly out of scope for v1
- Forcing portrait, reverse-landscape, or any orientation choice other than landscape (matches your "simple" requirement — one behavior, no per-app orientation picker).
- Split-screen, freeform windowing, foldable cover-screen handling.
- The Tier 1 (SDK-only) or Tier 3 (root/Xposed) paths.
- Cloud sync, backup/restore, multiple libraries/folders, themes, analytics, ads.
- Any behavior for apps that opt out of the aspect-ratio override (see risk above) beyond showing that they're unsupported.

---

## 3. User flows

**First run:** app explains in one short screen that it needs Shizuku to work, with a button that opens Shizuku's own pairing instructions (or the Play Store page if Shizuku isn't installed). Until Shizuku is connected, the Library screen is visible but the "add app" and "launch" actions are disabled with an inline explanation.

**Adding an app:** user taps a "+" on the Library screen → sees a searchable list of installed launchable apps (icon + name) → taps to select one or more → taps "Add" → they now appear in the Library grid.

**Launching an app:**
1. User taps an app in the Library grid.
2. Landscapify (via Shizuku) applies the orientation override for that package.
3. Landscapify launches the target app's normal launcher `Intent`.
4. A lightweight watcher tracks which app is in the foreground (via `UsageStatsManager`, polled only while a session is active — not continuously in the background).
5. Once the user has been away from the target app for a short debounce window (to tolerate quick task-switcher taps, notification shade pulls, etc. — a couple of seconds is enough), Landscapify reverts the override for that package via Shizuku.
6. If the user opens that same app again from its own home-screen icon later, it opens in its normal orientation, since no override is active outside a Landscapify-initiated session.

**Removing an app:** long-press or swipe a library entry → confirm removal. If a session for that app happens to be active, the override is reverted immediately as part of removal.

---

## 4. Architecture

- **Language/UI:** Kotlin, Jetpack Compose. Single-module app, simple MVVM (ViewModel per screen, no need for anything heavier at this scope).
- **Persistence:** a single local table (Room, or even a JSON blob in DataStore — either is fine at this scale) holding the library list. No backend, no network calls at all.
- **ShizukuManager:** a thin wrapper around the Shizuku API that handles binder connection, the runtime permission request, and exposes two operations: `applyLandscapeOverride(packageName)` and `revertOverride(packageName)`, implemented as Shizuku-privileged shell command execution (`Shizuku.newProcess(...)` running the equivalent of `am compat enable/disable <CHANGE_ID> <package>`) rather than raw AIDL/hidden-API reflection — simpler to build and debug.
- **ForegroundWatcher:** started only when a session begins (right after launching a target app) and stopped once that session ends; polls `UsageStatsManager` at a short interval (e.g. every 1–2 seconds) purely to detect when the foreground package has left the target app for longer than the debounce window. Not a permanent background service — it should not run at all when no session is active, to keep battery impact negligible.
- **AppPickerRepository:** wraps `PackageManager` queries for installed, launchable apps (respecting Android 11+ package-visibility rules — this needs either `QUERY_ALL_PACKAGES` or, preferably, a `<queries>` block in the manifest scoped to `ACTION_MAIN`/`CATEGORY_LAUNCHER` intents, which is the Play-Store-friendly option and should be preferred).

---

## 5. Data model

Single entity, `LibraryApp`:

| Field | Type | Notes |
|---|---|---|
| `packageName` | String (primary key) | e.g. `com.instagram.android` |
| `appName` | String | cached display label at add-time |
| `iconUri` / cached drawable | — | either re-resolve from `PackageManager` at render time, or cache a bitmap; re-resolving is simpler and avoids stale icons after app updates |
| `dateAdded` | Long (epoch) | for sort order |
| `overrideSupported` | Boolean | set based on whether the target app opts out of the aspect-ratio override (see §1 risk); drives the "unsupported" UI state |

No other entities are needed — there's no per-app settings beyond membership in the library for v1.

---

## 6. Permissions & special access

| Permission / access | Why | Notes |
|---|---|---|
| Shizuku runtime permission | Required to apply/revert the orientation override | Hard requirement, not optional |
| `PACKAGE_USAGE_STATS` (Usage Access) | Needed for the foreground watcher to detect session end | User grants via a Settings deep link; this is a "special access" permission, not a normal runtime prompt |
| `<queries>` manifest entries (or `QUERY_ALL_PACKAGES`) | To list installed launchable apps in the picker | Prefer the scoped `<queries>` approach — `QUERY_ALL_PACKAGES` draws extra Play Store review scrutiny |

**Distribution note:** if you intend to publish this on the Play Store, both the Usage Access requirement and the general "controls other apps' windowing" behavior may draw policy review. If this is for personal/sideload use (which seems likely given the workflow), that's a non-issue and this can be built and installed as a plain APK without any store-review constraints. Worth deciding explicitly before Milestone 1, since it doesn't change the app itself but does change how much you need to document/justify the permissions in-app.

---

## 7. Non-functional requirements

- No network access at all — everything is local and offline.
- No ads, no analytics, no accounts.
- Minimal permission footprint: only what's listed above, requested contextually (not all at first launch).
- The foreground watcher must not run when there's no active session — this is the main thing to get right for battery behavior.
- Graceful, clear UI state whenever Shizuku is disconnected or a specific app is unsupported — never a silent failure.
- Follows system light/dark theme; no custom theming needed beyond that.

---

## 8. Suggested milestones

1. **M0 — Technical spike (do this first):** confirm the exact shell-level command/change-ID that forces orientation via Shizuku on your actual target device(s)/Android version(s), and confirm the debounce-based foreground detection works reliably. This is the single point where the whole plan could need to change.
2. **M1 — App shell:** library storage, add/remove UI, app picker, empty states — no orientation logic yet, just the CRUD skeleton.
3. **M2 — Shizuku integration:** pairing flow, connection status UI, `ShizukuManager` wired to the M0 findings.
4. **M3 — Launch + session enforcement:** the full launch → override → watch → revert loop.
5. **M4 — Polish:** unsupported-app state, global pause toggle, minor UI pass.

---

## 9. Open questions to settle before/while building

- Minimum Android version to support (this gates which OS-level override mechanism is even available — likely Android 12+ at minimum, possibly higher depending on M0 findings).
- Play Store vs. sideload-only distribution (see §6 distribution note).
- Whether a couple of seconds of debounce on session-end is the right feel, or whether it should be tuned after real use.
