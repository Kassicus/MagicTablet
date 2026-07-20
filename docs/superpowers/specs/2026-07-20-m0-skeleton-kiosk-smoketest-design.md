# M0 — Skeleton + Kiosk Smoke-Test — Design Spec

**Date:** 2026-07-20
**Milestone:** M0 (first of M0–M6 in `DESIGN.md` §8)
**Status:** Approved scope, pending spec review

> This spec covers **only M0**. The master brief is `DESIGN.md` (the source of truth for the
> whole project). M0 delivers a running app on the target tablet plus early proof that the
> Device Owner kiosk assumption (`DESIGN.md` §6) holds — without any of the steps that could
> lock the device out of its own OS.

---

## 1. Purpose

Two outcomes, both meant to de-risk the rest of the build as early as possible:

1. **A running, installable app shell** on the tablet: single Activity, Compose, three-screen
   state-based navigation, landscape-locked, dark Material 3. This is the foundation every later
   milestone builds on.
2. **A kiosk smoke-test** that proves, on *this specific device*, that we can become Device Owner
   and cleanly relinquish it. This validates the biggest unknown in `DESIGN.md` §6 (the claim that
   the offline account preserves Device Owner provisioning) on day one instead of at M5.

M0 intentionally stops short of engaging Lock Task or launcher changes, so there is **zero risk of
being trapped in the app** during this milestone.

## 2. Target environment (verified 2026-07-20)

- **Device:** connected via USB, model `K12E_ROW` (Rockchip whitelabel; the "ECOPAD 10.1" brand
  skin), Android 15 / API 35. adb serial `TK12110626B081745`.
- **SDK:** `~/Library/Android/sdk` — `platforms/android-35`, `build-tools/36.0.0`, `platform-tools/adb`.
- **JDK:** Android Studio bundled JBR, Java 21.
- **adb:** standardize on `~/Library/Android/sdk/platform-tools/adb` (a second copy exists at
  `~/Documents/platform-tools/adb`; use only one to avoid adb-server version clashes).

## 3. Scope

**In scope (M0 ships):**

- Gradle project scaffold: Kotlin DSL, single `app` module, version catalog (`libs.versions.toml`),
  Gradle wrapper.
- Project config: `applicationId` / `namespace` `com.magictablet`, `minSdk 26`, `compileSdk 35`,
  `targetSdk 35`, landscape-locked, dark Material 3.
- `MainActivity` hosting Compose.
- `App()` composable: `Screen` enum (`Game` / `Cards` / `Stack`), a segmented top nav control,
  state-based switching between three placeholder screens. No Navigation-Compose dependency.
- Three placeholder screens (labeled; real features start M1+).
- Theme package (`Theme.kt`, `Color.kt`, `Type.kt`).
- `AdminReceiver` (`DeviceAdminReceiver`) + manifest declaration + `res/xml/device_admin.xml`.
  This is the **only** kiosk plumbing in M0.
- An in-app **"Relinquish device owner"** maintenance action (on the Game screen) that calls
  `clearDeviceOwnerApp(packageName)`.

**Out of scope (explicitly deferred):**

- Lock Task / `startLockTask` (M5).
- HOME/DEFAULT launcher categories, disabling `launcher3` (M5).
- `FLAG_KEEP_SCREEN_ON` (M5).
- Room, the bundled card/ruling SQLite, FTS, any data layer (M2+).
- Any real Game / Cards / Stack feature UI (M1, M3, M4).

## 4. Architecture

- **Single-module** Gradle project; one `app` module. Dependencies limited to Compose (BOM),
  Material 3, and the Compose activity integration. Nothing else yet.
- **Single Activity.** `MainActivity` calls `setContent { MagicTabletTheme { App() } }`.
- **State-based navigation.** `App()` owns `var screen by remember { mutableStateOf(Screen.Game) }`.
  A top segmented control sets `screen`; a `when (screen)` renders the matching placeholder. This is
  the lightweight nav the brief prescribes (`DESIGN.md` §3) — no nav library.
- **Landscape + dark** enforced at the manifest/theme level, not per-screen.
- **Kiosk plumbing is inert in M0.** `AdminReceiver` exists and can be provisioned as Device Owner
  via adb, but the app never calls `startLockTask()` and never registers as HOME. The only
  Device-Policy call the app makes is `clearDeviceOwnerApp` from the maintenance action.

## 5. Components

| File | Responsibility |
|------|----------------|
| `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml` | Project + dependency wiring. |
| `app/build.gradle.kts` | App id, SDK levels, Compose, signing (debug). |
| `app/src/main/AndroidManifest.xml` | `MainActivity` (LAUNCHER, landscape), app theme/label, `AdminReceiver` with `BIND_DEVICE_ADMIN` + `device_admin` meta-data. **No HOME categories in M0.** |
| `res/xml/device_admin.xml` | Device admin policy declaration (force-lock etc.). |
| `MainActivity.kt` | Hosts Compose, applies theme. |
| `App.kt` | `Screen` enum, top nav control, state-based screen switch. |
| `GameScreen.kt` | Placeholder + the "Relinquish device owner" maintenance action. |
| `CardsScreen.kt`, `StackScreen.kt` | Labeled placeholders. |
| `ui/theme/Theme.kt`, `Color.kt`, `Type.kt` | Dark Material 3 theme. |
| `AdminReceiver.kt` | `DeviceAdminReceiver` subclass (empty overrides fine for M0). |

## 6. Kiosk smoke-test procedure (M0's real payload)

Run after a successful install. All steps reversible; no Lock Task is engaged at any point.

1. **Install:** `./gradlew :app:installDebug` (or `adb install` the built APK).
2. **Provision:** `adb shell dpm set-device-owner com.magictablet/.AdminReceiver`
   → **must** print `Success`. An accounts-related error here is the single most valuable thing we
   could learn this early (it would mean §6's provisioning assumption is wrong on this device).
3. **Verify:** `adb shell dumpsys device_policy` shows device owner = `com.magictablet`.
4. **Relinquish:** in-app, tap **Relinquish device owner** → `clearDeviceOwnerApp("com.magictablet")`
   → re-check `dumpsys device_policy` shows no device owner.

**Note on re-provisioning:** `set-device-owner` requires no accounts and no existing device owner.
After relinquishing, re-running step 2 should succeed given the device stays account-free; if it
does not, that constraint is itself a useful M0 finding to record for M5 planning.

## 7. Definition of done (M0)

- [ ] App builds from the command line against the installed SDK.
- [ ] App installs and launches on `TK12110626B081745`.
- [ ] Three placeholder screens switch via the top nav control.
- [ ] App renders landscape-locked, dark Material 3.
- [ ] `adb shell dpm set-device-owner com.magictablet/.AdminReceiver` prints `Success` on this device.
- [ ] In-app **Relinquish device owner** cleanly removes device owner (verified via `dumpsys`).

## 8. Risks / open items

- **Device Owner accounts constraint** — the whole point of the smoke-test; resolved by running it.
- **build-tools 36 vs compileSdk 35** — expected fine; AGP selects an available build-tools. Pin
  only if AGP complains.
- **Gradle wrapper bootstrap** — no system Gradle; the wrapper (jar + scripts) must be created so
  `./gradlew` can download its distribution on first run. Handled during implementation.
- **Two adb binaries** — use the SDK's exclusively.

## 9. References

- `DESIGN.md` — master brief (§3 tech stack, §6 kiosk, §8 build order).
