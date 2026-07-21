# In-App Kiosk Enter/Exit — Design Spec

**Date:** 2026-07-20
**Milestone:** Post-v1 iteration (builds on M5 kiosk hardening). UI + a small kiosk-logic change; no model/persistence change, no new deps.
**Status:** Approved design, pending spec review

> Adds an in-app way to **enter** kiosk (Lock Task) and makes **exit** keep device owner so you can re-enter,
> separating that toggle from the maintenance **relinquish device owner** action. Resolves the M5 asymmetry where
> the only in-app control ("Exit kiosk mode") relinquished device owner entirely, leaving no way back in without `adb`.

---

## 1. Purpose

Give the shared tablet an in-app kiosk **toggle**: Enter Lock Task / Exit Lock Task while **staying device owner**
(re-enterable), plus a separate confirmation-gated **Relinquish device owner** for maintenance/updates. When the app
isn't provisioned as device owner yet, "Enter kiosk mode" explains the one-time `adb` provisioning (an app cannot
self-promote to device owner — OS security).

**Not in scope (decided):** a signed release build / keystore — the debug build persists on-device after USB
disconnect (a normal `/data/app` install; USB is only the delivery channel), so no "permanent install" change is
needed. No model/persistence change; no new dependencies.

## 2. Background / constraint

- An app **cannot** make itself device owner; `dpm set-device-owner` is a one-time `adb` (or QR/NFC) provisioning
  step on an account-free device. Once provisioned, starting/stopping **Lock Task** is fully in-app.
- Today `kiosk/Kiosk.kt` has `enterLockTaskIfNeeded` (auto-enter on `MainActivity.onResume` when owner) and
  `releaseKiosk` (stopLockTask + `clearDeviceOwnerApp`). The Game panel's "Exit kiosk mode" calls `releaseKiosk` —
  i.e. it fully relinquishes owner, so re-entry needs `adb` again. This spec splits those concerns.

## 3. `kiosk/Kiosk.kt` changes

Keep `adminComponent`, `isDeviceOwner`, `shouldStartLockTask` (pure, unit-tested), `configureLockTask`,
`enterLockTaskIfNeeded` (still used by `MainActivity.onResume`), `releaseKiosk` (now the "relinquish" action), and
`findActivity`. Add three thin platform helpers:

- `fun inLockTask(activity: Activity): Boolean` — `activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE`.
- `fun enterKiosk(activity: Activity)` — `configureLockTask(activity)` then `enterLockTaskIfNeeded(activity)`
  (idempotent; `startLockTask` only when owner-permitted-not-already-locked, per the existing guard). No-op on a
  non-owner build (the UI gates this behind `isDeviceOwner`).
- `fun exitKiosk(activity: Activity)` — `stopLockTask()` only when currently in Lock Task; **does NOT** touch device
  owner. So the app stays owner and can re-enter.

`releaseKiosk` is unchanged (the maintenance full un-kiosk).

## 4. Game panel UI (`screens/GameScreen.kt`)

The left slide-out panel's bottom section (after `Spacer(Modifier.weight(1f))`) changes from the single
"Exit kiosk mode" button to:

- **Kiosk toggle** — its label reflects the current Lock Task state, read when the panel composes
  (`val inKiosk = inLockTask(activity)`; the panel recomposes each time it opens, so the label is fresh):
  - If `inKiosk` → **"Exit kiosk mode"** → `exitKiosk(activity)` + toast "Left kiosk — still device owner"; close panel.
  - Else → **"Enter kiosk mode"** → if `isDeviceOwner(context)` then `enterKiosk(activity)` + toast "Kiosk mode on";
    else set `showProvisionInfo = true` (dialog below); close panel.
- **"Relinquish device owner"** button → set `showRelinquish = true` (the existing confirm dialog, renamed from
  `showExitKiosk`); close panel.

Dialogs:
- **Relinquish confirm** (`showRelinquish`) — same `AlertDialog` as today: title "Relinquish device owner?", body
  "This unlocks the tablet and removes MTG Table as device owner, so players can leave the app. Continue?", confirm →
  `releaseKiosk(context.findActivity())` + toast; dismiss → cancel.
- **Provisioning info** (`showProvisionInfo`) — new `AlertDialog`: title "Enter kiosk mode", body explaining the app
  isn't device owner so it can't lock itself, and to run once over USB:
  `adb shell dpm set-device-owner com.magictablet/.AdminReceiver` (tablet must be account-free), then reopen the app;
  a single "OK" button dismisses.

`context`/`findActivity()` are already available in `GameScreen`. No other screen changes.

## 5. Behavior

- **Provisioned (device owner):** launching still auto-enters Lock Task (`MainActivity.onResume`, unchanged). In the
  panel, "Exit kiosk mode" leaves Lock Task but **stays owner**; "Enter kiosk mode" re-enters — the new capability.
  "Relinquish device owner" (confirm) fully un-kiosks for maintenance.
- **Not provisioned:** "Enter kiosk mode" shows the `adb` provisioning dialog. (After running it once + reopening,
  the app is owner and the toggle works.)

## 6. Testing

- **Unit:** none new — the only pure logic (`shouldStartLockTask`) is already covered; `enterKiosk`/`exitKiosk`/
  `inLockTask` are thin platform wrappers (device-only). Confirm the suite stays green.
- **On-device (the gate; needs provisioning):**
  1. Fresh (not owner): panel → "Enter kiosk mode" → the provisioning dialog shows the `adb` command.
  2. Provision: `adb shell dpm set-device-owner com.magictablet/.AdminReceiver` (Success), reopen.
  3. Panel now shows **"Exit kiosk mode"** (auto-entered on launch); tap it → leaves Lock Task; `adb shell dpm list-owners`
     still shows the owner (exit kept ownership).
  4. Panel now shows **"Enter kiosk mode"** → tap → re-enters Lock Task (can't leave the app). **This is the new fix.**
  5. Panel → **"Relinquish device owner"** → confirm → owner removed (`dpm list-owners` empty), tablet navigable.

## 7. Risks / seams

- **`inLockTask` read at composition** is a point-in-time system read; fine because each panel action closes the
  panel, so reopening re-reads current state. No StateFlow needed.
- **Non-owner `enterKiosk`** is a guarded no-op (UI shows the dialog instead), so a dev build never triggers a broken
  screen-pin. `exitKiosk` only stops Lock Task if in it.
- **Escape hatch preserved:** in-app "Relinquish device owner" + `adb dpm remove-active-admin` remain the ways out.

## 8. References

- `kiosk/Kiosk.kt` (M5): `enterLockTaskIfNeeded`, `releaseKiosk`, `shouldStartLockTask`, `findActivity`.
- `screens/GameScreen.kt` (post-UI-refresh): the left panel + the current `showExitKiosk` confirm dialog.
- DESIGN.md §6 (kiosk / Device Owner in-app requirements; the maintenance-relinquish mandate).
