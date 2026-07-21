# In-App Kiosk Enter/Exit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-app kiosk **toggle** — Enter/Exit Lock Task while keeping device owner (so it's re-enterable) — plus a separate confirmation-gated **Relinquish device owner**, and a provisioning dialog when the tablet isn't device owner yet.

**Architecture:** Three thin platform helpers in `kiosk/Kiosk.kt` (`inLockTask`, `enterKiosk`, `exitKiosk`) split "leave Lock Task" from the existing `releaseKiosk` ("relinquish owner"). The Game panel's bottom becomes a state-aware toggle + a relinquish button + a provisioning dialog. No ViewModel/model/persistence change, no new deps.

**Tech Stack:** Kotlin, Android `DevicePolicyManager`/`ActivityManager` Lock Task APIs, Jetpack Compose (Material 3).

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-kiosk-enter-exit-design.md`):

- **No new deps; no VM/model/persistence change.** An app **cannot** self-promote to device owner — `dpm set-device-owner` is a one-time `adb` step; entering/exiting Lock Task is in-app only once provisioned.
- `exitKiosk` must **NOT** touch device owner (only `stopLockTask`), so the app stays owner and can re-enter. `releaseKiosk` (unchanged) remains the full relinquish.
- `enterKiosk` is guarded (start Lock Task only when owner-permitted-not-locked) — the UI gates it behind `isDeviceOwner`, showing the provisioning dialog otherwise.
- **Environment preamble** (Gradle/adb; pass `dangerouslyDisableSandbox: true`):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```
  Device serial: `TK12110626B081745`.

> **Testing:** No new unit tests (the only pure logic, `shouldStartLockTask`, is already covered; the new helpers are thin platform wrappers). Confirm the suite stays green + verify the kiosk lifecycle on-device (needs `adb` provisioning). Leave the device clean (no device owner) at the end.

---

### Task 1: Kiosk enter/exit helpers + Game panel toggle

**Files:**
- Modify: `app/src/main/java/com/magictablet/kiosk/Kiosk.kt`
- Modify: `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- Produces: `inLockTask(Activity): Boolean`, `enterKiosk(Activity)`, `exitKiosk(Activity)` (consumed by `GameScreen`).

- [ ] **Step 1: Add the helpers to `Kiosk.kt`** — insert immediately after the `enterLockTaskIfNeeded` function (i.e. after its closing `}` and before `fun releaseKiosk`):

```kotlin

/** True when the app is currently in a Lock Task (kiosk) session. */
fun inLockTask(activity: Activity): Boolean =
    activityManager(activity).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

/** Enter kiosk: ensure the package is whitelisted, then start Lock Task (guarded; no-op if not owner). */
fun enterKiosk(activity: Activity) {
    configureLockTask(activity)
    enterLockTaskIfNeeded(activity)
}

/** Leave kiosk: stop Lock Task but KEEP device owner, so the app can re-enter later. */
fun exitKiosk(activity: Activity) {
    if (inLockTask(activity)) activity.stopLockTask()
}
```

- [ ] **Step 2: `GameScreen.kt` — imports** — replace:

```kotlin
import com.magictablet.kiosk.findActivity
import com.magictablet.kiosk.releaseKiosk
```
with:
```kotlin
import com.magictablet.kiosk.enterKiosk
import com.magictablet.kiosk.exitKiosk
import com.magictablet.kiosk.findActivity
import com.magictablet.kiosk.inLockTask
import com.magictablet.kiosk.isDeviceOwner
import com.magictablet.kiosk.releaseKiosk
```

- [ ] **Step 3: `GameScreen.kt` — state vars** — replace:

```kotlin
    var showExitKiosk by remember { mutableStateOf(false) }
```
with:
```kotlin
    var showRelinquish by remember { mutableStateOf(false) }
    var showProvisionInfo by remember { mutableStateOf(false) }
```

- [ ] **Step 4: `GameScreen.kt` — panel bottom (the kiosk toggle + relinquish)** — replace:

```kotlin
                    Spacer(Modifier.weight(1f))
                    PanelButton("Exit kiosk mode") { showExitKiosk = true; panelOpen = false }
```
with:
```kotlin
                    Spacer(Modifier.weight(1f))
                    val activity = context.findActivity()
                    if (inLockTask(activity)) {
                        PanelButton("Exit kiosk mode") {
                            exitKiosk(activity)
                            Toast.makeText(context, "Left kiosk — still device owner", Toast.LENGTH_LONG).show()
                            panelOpen = false
                        }
                    } else {
                        PanelButton("Enter kiosk mode") {
                            if (isDeviceOwner(context)) {
                                enterKiosk(activity)
                                Toast.makeText(context, "Kiosk mode on", Toast.LENGTH_LONG).show()
                            } else {
                                showProvisionInfo = true
                            }
                            panelOpen = false
                        }
                    }
                    PanelButton("Relinquish device owner") { showRelinquish = true; panelOpen = false }
```

- [ ] **Step 5: `GameScreen.kt` — dialogs (relinquish confirm + provisioning info)** — replace the whole existing `if (showExitKiosk) { ... }` block:

```kotlin
    if (showExitKiosk) {
        AlertDialog(
            onDismissRequest = { showExitKiosk = false },
            title = { Text("Exit kiosk mode?") },
            text = { Text("This unlocks the tablet and removes MTG Table as device owner, so players can leave the app. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showExitKiosk = false
                    val message = releaseKiosk(context.findActivity())
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }) { Text("Exit kiosk") }
            },
            dismissButton = {
                TextButton(onClick = { showExitKiosk = false }) { Text("Cancel") }
            },
        )
    }
```
with:
```kotlin
    if (showRelinquish) {
        AlertDialog(
            onDismissRequest = { showRelinquish = false },
            title = { Text("Relinquish device owner?") },
            text = { Text("This unlocks the tablet and removes MTG Table as device owner, so players can leave the app. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showRelinquish = false
                    val message = releaseKiosk(context.findActivity())
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }) { Text("Relinquish") }
            },
            dismissButton = {
                TextButton(onClick = { showRelinquish = false }) { Text("Cancel") }
            },
        )
    }
    if (showProvisionInfo) {
        AlertDialog(
            onDismissRequest = { showProvisionInfo = false },
            title = { Text("Enter kiosk mode") },
            text = {
                Text(
                    "MTG Table isn't the device owner yet, so it can't lock itself. On an account-free tablet, run " +
                        "this once over USB:\n\nadb shell dpm set-device-owner com.magictablet/.AdminReceiver\n\n" +
                        "then reopen the app and use Enter kiosk mode.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showProvisionInfo = false }) { Text("OK") }
            },
        )
    }
```

- [ ] **Step 6: Build + install + confirm suite green**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Expected: unit suite BUILD SUCCESSFUL; app installs and launches.

- [ ] **Step 7: On-device kiosk lifecycle (the gate) — leave the device CLEAN at the end**

Confirm the current owner state first: `"$ADB" -s TK12110626B081745 shell dpm list-owners`.
1. **Not owner:** open the left panel → the bottom shows **"Enter kiosk mode"**; tap it → the provisioning dialog shows the `adb` command; dismiss.
2. **Provision:** `"$ADB" -s TK12110626B081745 shell dpm set-device-owner com.magictablet/.AdminReceiver` (expect `Success`). Relaunch the app.
3. On launch it auto-enters Lock Task (M5 onResume). Open the panel → the bottom now shows **"Exit kiosk mode"**; tap it → the app leaves Lock Task (`dumpsys activity | grep -i lockTask` → NONE) but `dpm list-owners` **still shows the owner** (exit kept ownership). Toast "Left kiosk — still device owner".
4. Open the panel → it now shows **"Enter kiosk mode"** → tap → the app **re-enters Lock Task** (can't leave). **This is the new capability** — verify HOME/back can't leave.
5. Open the panel → **"Relinquish device owner"** → confirm → `dpm list-owners` is **empty**, tablet navigable.
6. **Leave clean:** ensure the end state is not device owner and not locked. If anything wedges, escape with `"$ADB" -s TK12110626B081745 shell dpm remove-active-admin com.magictablet/.AdminReceiver`. Report exactly what you observed at each step (owner state before/after exit is the key check).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Kiosk: in-app Enter/Exit toggle (keeps device owner) + separate Relinquish + provisioning dialog"
```

---

## Definition of Done

- [ ] The Game panel shows a state-aware **Enter/Exit kiosk** toggle; **Exit keeps device owner** so **Enter** re-enters Lock Task.
- [ ] A separate **Relinquish device owner** (confirm) fully un-kiosks; "Enter" when not owner shows the `adb` provisioning dialog.
- [ ] Unit suite green; the on-device lifecycle (provision → exit-stays-owner → **re-enter** → relinquish) verified; device left clean.

## Self-Review notes

- **Spec coverage:** `inLockTask`/`enterKiosk`/`exitKiosk` (§3) + the panel toggle/relinquish/provision dialog (§4). All spec items mapped.
- **Type consistency:** `enterKiosk`/`exitKiosk`/`inLockTask`/`isDeviceOwner`/`releaseKiosk`/`findActivity` all `(Activity)`/`(Context)` as used in `GameScreen`. `showExitKiosk` fully renamed to `showRelinquish` (no dangling reference); `showProvisionInfo` added and hosted.
- **Key behavior:** `exitKiosk` = `stopLockTask` only (no `clearDeviceOwnerApp`) → owner preserved → re-enter works. That's the whole point; the on-device Step 7 explicitly checks `dpm list-owners` after Exit.
- **No new pure logic** → no new unit tests; gate is the device lifecycle + suite staying green.
- **Known seam:** `inLockTask(activity)` is read at panel composition; fine because each panel action closes the panel, so reopening re-reads current state (no StateFlow needed).
