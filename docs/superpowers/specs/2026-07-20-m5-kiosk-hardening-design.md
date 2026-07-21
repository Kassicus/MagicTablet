# M5 — Kiosk Hardening — Design Spec

**Date:** 2026-07-20
**Milestone:** M5 (DESIGN.md §6 + §8). Builds on M0 (Device Owner receiver + provisioning, proven on-device).
**Status:** Approved design, pending spec review

> Master brief: `DESIGN.md` §6 (kiosk / Device Owner in-app requirements) + §8 (build order) + §10 (v1 DoD:
> "a locked-down tablet that boots straight into the app … with no way to exit to the rest of the OS").
> M5 turns the working app into a true kiosk: Lock Task, HOME launcher, keep-screen-on, and a guarded
> maintenance exit. M0 already proved Device Owner provisioning + `clearDeviceOwnerApp` relinquish work on
> the device; M5 adds the lock-task + HOME + keep-on layer on top.

---

## 1. Purpose

Make the tablet run locked to MagicTablet and survive reboots: as Device Owner, whitelist + enter Lock Task
so players can't leave; register the app as the HOME launcher so it's the only thing that ever shows; hold the
screen awake; and provide a **confirmation-guarded** in-app maintenance exit (relinquish Device Owner) for
updates. Disabling the stock launcher and provisioning Device Owner remain external `adb` steps (documented
runbook), done *after* this ships.

## 2. Scope

**In scope:**
- A single kiosk-logic unit (`kiosk/Kiosk.kt`) with one pure, unit-tested guard (`shouldStartLockTask`).
- `MainActivity`: `FLAG_KEEP_SCREEN_ON`; configure + enter Lock Task (guarded to Device Owner only).
- Manifest: HOME + DEFAULT categories on `MainActivity`; `allowBackup=false`; real device-admin description.
- ⚙-hub maintenance exit: **confirmation dialog** → stop Lock Task + relinquish Device Owner.
- An operational runbook (§8) for the external `adb` provisioning + launcher-disable + reboot verification.

**Out of scope:**
- Auto-launch-on-boot beyond being HOME (being the HOME app *is* the boot-into-app mechanism; no BOOT_COMPLETED receiver).
- Configurable Lock Task features / allow-listing other packages (default = most locked).
- OTA/self-update, remote management, MDM. Idle-sleep / battery timeout (decided: **always-on**, §5).
- Room persistence of game state across the kiosk reboot (that's M6).

## 3. Decisions (from brainstorming)

- **Keep-screen-on = always on.** Hold `FLAG_KEEP_SCREEN_ON` whenever the app window is visible. In kiosk mode
  the app is always foreground, so the screen effectively never sleeps — accepted for simplicity (battery cost
  accepted; the tablet is a powered table centerpiece).
- **Maintenance exit = confirmation dialog.** The exit stays in the ⚙ hub but requires a confirm before it
  relinquishes Device Owner (it is the only way out of the kiosk once locked).

## 4. Architecture

- **`kiosk/Kiosk.kt`** (`com.magictablet.kiosk`) — top-level functions; the only place `DevicePolicyManager` /
  Lock Task lives (keeps `MainActivity` and `GameScreen` thin):
  - `adminComponent(Context): ComponentName` — `ComponentName(context, AdminReceiver::class.java)`.
  - `isDeviceOwner(Context): Boolean` — `dpm.isDeviceOwnerApp(packageName)`.
  - `shouldStartLockTask(isDeviceOwner, lockTaskPermitted, lockTaskState): Boolean` — **pure, unit-tested** =
    `isDeviceOwner && lockTaskPermitted && lockTaskState == ActivityManager.LOCK_TASK_MODE_NONE`.
  - `configureLockTask(Activity)` — if Device Owner, `setLockTaskPackages(admin, arrayOf(packageName))`; else no-op.
  - `enterLockTaskIfNeeded(Activity)` — reads live system state (`isDeviceOwnerApp`, `isLockTaskPermitted`,
    `ActivityManager.lockTaskModeState`), and calls `startLockTask()` only when `shouldStartLockTask` is true.
  - `releaseKiosk(Activity): String` — `stopLockTask()` if in Lock Task, then `clearDeviceOwnerApp()` if owner;
    returns a status message for a toast.
  - `Context.findActivity(): Activity` — unwrap `ContextWrapper`s so the Compose `LocalContext` resolves to the
    hosting Activity (needed for `stopLockTask`).
- **`MainActivity`** — `onCreate`: `window.addFlags(FLAG_KEEP_SCREEN_ON)` + `configureLockTask(this)` (before
  `setContent`). `onResume`: `enterLockTaskIfNeeded(this)` (idempotent; no-op on non-owner dev builds and when
  already locked).
- **`AndroidManifest.xml`** — `MainActivity` intent-filter gains `HOME` + `DEFAULT` categories (kept alongside
  `LAUNCHER`); `application android:allowBackup="false"`; receiver `android:description="@string/device_admin_description"`.
- **`res/values/strings.xml`** — new `device_admin_description`.
- **`GameScreen` ⚙ hub** — the current one-tap "Relinquish device owner" item becomes **"Exit kiosk mode"** →
  sets `showExitKiosk`; an `AlertDialog` confirms, then calls `releaseKiosk(context.findActivity())` + toast.
  The old private `relinquishDeviceOwner(context)` and its `DevicePolicyManager` import are removed (logic moves
  to `Kiosk.kt`).

## 5. Behavior / lifecycle

- **Boot / normal use:** HOME resolves to `MainActivity` → `onCreate` sets keep-screen-on + whitelists the
  package for Lock Task → `onResume` enters Lock Task → players are locked in, screen stays awake. System back
  works within the app but can't leave; HOME/recents are disabled by Lock Task.
- **Dev build (not Device Owner):** every device-owner/lock-task call is guarded → nothing locks, the app runs
  normally; `FLAG_KEEP_SCREEN_ON` still applies (harmless in dev).
- **Maintenance exit:** ⚙ → Exit kiosk mode → confirm → `stopLockTask()` + `clearDeviceOwnerApp()` → the tablet
  is a normal device again (can install updates / re-provision). This is the escape hatch.

## 6. Error handling / guards

- `configureLockTask` / `enterLockTaskIfNeeded` / `releaseKiosk` all re-check `isDeviceOwnerApp` live before any
  privileged call — no crash if run on a non-owner build.
- `startLockTask()` is gated by `shouldStartLockTask` (owner + `isLockTaskPermitted` + not already in Lock Task),
  so it never triggers the screen-pinning confirmation path and is safe to call every `onResume`.
- `releaseKiosk` stops Lock Task only when `lockTaskModeState != LOCK_TASK_MODE_NONE`, then relinquishes; if not
  owner it still returns a sensible message. `clearDeviceOwnerApp` stays `@Suppress("DEPRECATION")` (as today).

## 7. Testing

- **Unit (JVM), `KioskTest`:** `shouldStartLockTask` truth table — owner+permitted+NONE → true; owner+permitted+
  LOCKED → false (already locked); not-owner → false; owner+not-permitted → false. (`LOCK_TASK_MODE_*` are
  compile-time `static final int` constants, inlined into the test — no Android runtime needed.)
- **On-device manual (the real gate — higher-stakes; the guarded exit is the escape):**
  1. `installDebug`; provision: `adb shell dpm set-device-owner com.magictablet/.AdminReceiver` (Success).
  2. Launch → app enters Lock Task: HOME/recents/back can't leave the app; status/nav locked.
  3. Confirm the screen stays on (no sleep during play).
  4. Reboot the tablet → it boots straight into MagicTablet (HOME), still locked, screen on.
  5. ⚙ → **Exit kiosk mode** → confirm → app leaves Lock Task and `isDeviceOwnerApp` becomes false (toast); the
     tablet is navigable again. Re-provision to restore kiosk.
  6. (Optional, external) after HOME is verified, disable the stock launcher so ours is the sole HOME:
     `adb shell pm disable-user --user 0 <stock-launcher-pkg>` (e.g. `com.android.launcher3`) — reversible with
     `pm enable`.

## 8. Provisioning runbook (external `adb`, documented — not in-app)

Order matters (DESIGN.md §6: disable the stock launcher only *after* HOME is set):
1. `adb install` / `./gradlew :app:installDebug`.
2. `adb shell dpm set-device-owner com.magictablet/.AdminReceiver` (device must be account-free — already done).
3. Launch once; verify Lock Task + that pressing HOME keeps/returns to MagicTablet.
4. `adb shell pm disable-user --user 0 <stock-launcher-pkg>` to make MagicTablet the only HOME.
5. Reboot; verify boot-into-app.
6. To un-kiosk for maintenance: in-app ⚙ → Exit kiosk mode (or `adb shell dpm remove-active-admin …` as fallback).

## 9. Definition of done (M5)

- [ ] As Device Owner, the app enters Lock Task on launch and cannot be left; a dev (non-owner) build is unaffected.
- [ ] `MainActivity` is registered as HOME (+DEFAULT) so the device can boot straight into the app.
- [ ] The screen stays on while the app is foreground (always-on).
- [ ] ⚙ hub "Exit kiosk mode" shows a confirmation, then stops Lock Task + relinquishes Device Owner.
- [ ] `allowBackup=false`; the device-admin has a real description string.
- [ ] `KioskTest` passes; the on-device kiosk lifecycle (enter → reboot-into-app → screen-on → guarded exit) is verified.

## 10. Risks / seams

- **Locking yourself in:** once Lock Task + HOME + launcher-disabled, the *only* in-app way out is the ⚙ exit;
  `adb` (`dpm remove-active-admin` / `pm enable <launcher>`) is the external fallback. The confirmation dialog
  prevents accidental exits without hiding the path.
- **Always-on battery:** accepted per §3; revisit with an idle timeout later if the tablet isn't always powered.
- **`clearDeviceOwnerApp` deprecated:** retained (works on the target OS, proven in M0); no replacement needed for
  a single-app kiosk.
- **HOME chooser on dev devices:** registering HOME shows a launcher chooser on a normal phone — benign for dev.

## 11. References

- `DESIGN.md` §6 (kiosk in-app requirements), §8 (M5 line), §10 (v1 DoD).
- M0: `AdminReceiver` + `res/xml/device_admin.xml` (force-lock) + the ⚙ relinquish (`clearDeviceOwnerApp`),
  device-proven. Android Lock Task: `DevicePolicyManager.setLockTaskPackages` / `Activity.startLockTask` /
  `stopLockTask` / `ActivityManager.getLockTaskModeState`.
