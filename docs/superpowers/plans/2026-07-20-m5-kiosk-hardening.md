# M5 — Kiosk Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn MagicTablet into a true kiosk: as Device Owner it enters Lock Task on launch, registers as the HOME launcher, keeps the screen on, and offers a confirmation-guarded in-app exit that relinquishes Device Owner.

**Architecture:** One kiosk-logic unit (`kiosk/Kiosk.kt`) holds all `DevicePolicyManager`/Lock Task calls behind a single pure, unit-tested guard (`shouldStartLockTask`). `MainActivity` wires keep-screen-on + the Lock Task lifecycle; the manifest adds HOME/DEFAULT + hardening; the ⚙ hub gets a confirm-then-exit dialog. Every privileged call is guarded to Device Owner, so dev builds are unaffected.

**Tech Stack:** Kotlin, Android `DevicePolicyManager` / `ActivityManager` Lock Task APIs, Jetpack Compose (Material 3). No new deps.

## Global Constraints

From the spec (`docs/superpowers/specs/2026-07-20-m5-kiosk-hardening-design.md`):

- Package `com.magictablet.kiosk` for kiosk logic. **No new dependencies, no new permissions.** App ID `com.magictablet`.
- **Keep-screen-on = always on** (`FLAG_KEEP_SCREEN_ON` while foreground). **Exit = confirmation dialog** (the only in-app way out of the kiosk).
- Every device-owner / Lock Task call MUST be guarded by a live `isDeviceOwnerApp` check so a **non-owner dev build never locks and never crashes**. `startLockTask()` only when owner **and** `isLockTaskPermitted` **and** not already in Lock Task.
- Disabling the stock launcher + `dpm set-device-owner` are **external `adb` steps** (runbook in spec §8), NOT in-app code.
- `clearDeviceOwnerApp` stays `@Suppress("DEPRECATION")` (works on the target OS; proven in M0).
- **Environment preamble** (for every Gradle/adb command; pass `dangerouslyDisableSandbox: true`):

  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export PATH="$JAVA_HOME/bin:$PATH"
  ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
  ```
  Device serial: `TK12110626B081745`.

> **Testing:** Task 1 = offline JVM unit test (the pure guard, TDD). Task 2 = wiring + manifest + UI, then the on-device kiosk lifecycle (spec §7). The on-device pass is higher-stakes — it locks the tablet into the app; the ⚙ confirm-exit (and `adb dpm remove-active-admin`) is the escape hatch.

---

### Task 1: `kiosk/Kiosk.kt` + the `shouldStartLockTask` guard (TDD)

**Files:**
- Create: `app/src/main/java/com/magictablet/kiosk/Kiosk.kt`
- Test: `app/src/test/java/com/magictablet/kiosk/KioskTest.kt`

**Interfaces:**
- Produces: `adminComponent(Context): ComponentName`, `isDeviceOwner(Context): Boolean`,
  `shouldStartLockTask(isDeviceOwner: Boolean, lockTaskPermitted: Boolean, lockTaskState: Int): Boolean`,
  `configureLockTask(Activity)`, `enterLockTaskIfNeeded(Activity)`, `releaseKiosk(Activity): String`,
  `Context.findActivity(): Activity`. Task 2 consumes all of these.

- [ ] **Step 1: Write the failing test** — `app/src/test/java/com/magictablet/kiosk/KioskTest.kt`

```kotlin
package com.magictablet.kiosk

import android.app.ActivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskTest {
    @Test fun startsWhenOwnerPermittedAndNotLocked() {
        assertTrue(shouldStartLockTask(true, true, ActivityManager.LOCK_TASK_MODE_NONE))
    }

    @Test fun noStartWhenAlreadyLocked() {
        assertFalse(shouldStartLockTask(true, true, ActivityManager.LOCK_TASK_MODE_LOCKED))
    }

    @Test fun noStartWhenNotOwner() {
        assertFalse(shouldStartLockTask(false, true, ActivityManager.LOCK_TASK_MODE_NONE))
    }

    @Test fun noStartWhenNotPermitted() {
        assertFalse(shouldStartLockTask(true, false, ActivityManager.LOCK_TASK_MODE_NONE))
    }
}
```

(`LOCK_TASK_MODE_NONE`/`_LOCKED` are `public static final int` constants — javac inlines them into the test bytecode, so no Android runtime is needed to run this JVM test.)

- [ ] **Step 2: Run the test, verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.kiosk.KioskTest"
```
Expected: FAIL — unresolved reference `shouldStartLockTask`.

- [ ] **Step 3: Create `app/src/main/java/com/magictablet/kiosk/Kiosk.kt`**

```kotlin
package com.magictablet.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import com.magictablet.AdminReceiver

/** The device-admin component for this app (the receiver provisioned as Device Owner). */
fun adminComponent(context: Context): ComponentName =
    ComponentName(context, AdminReceiver::class.java)

private fun dpm(context: Context): DevicePolicyManager =
    context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

private fun activityManager(context: Context): ActivityManager =
    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

fun isDeviceOwner(context: Context): Boolean =
    dpm(context).isDeviceOwnerApp(context.packageName)

/**
 * Pure guard: start Lock Task only when we own the device, this package is permitted for Lock Task,
 * and we are not already in a lock-task session. Unit-tested.
 */
fun shouldStartLockTask(isDeviceOwner: Boolean, lockTaskPermitted: Boolean, lockTaskState: Int): Boolean =
    isDeviceOwner && lockTaskPermitted && lockTaskState == ActivityManager.LOCK_TASK_MODE_NONE

/** As Device Owner, whitelist this package for Lock Task. No-op on a non-owner (dev) build. */
fun configureLockTask(activity: Activity) {
    val policy = dpm(activity)
    if (policy.isDeviceOwnerApp(activity.packageName)) {
        policy.setLockTaskPackages(adminComponent(activity), arrayOf(activity.packageName))
    }
}

/** Enter Lock Task if the guard permits. Idempotent; safe to call every onResume and on dev builds. */
fun enterLockTaskIfNeeded(activity: Activity) {
    val policy = dpm(activity)
    val start = shouldStartLockTask(
        isDeviceOwner = policy.isDeviceOwnerApp(activity.packageName),
        lockTaskPermitted = policy.isLockTaskPermitted(activity.packageName),
        lockTaskState = activityManager(activity).lockTaskModeState,
    )
    if (start) activity.startLockTask()
}

/** Maintenance exit: leave Lock Task (if in it), then relinquish Device Owner. Returns a status message. */
fun releaseKiosk(activity: Activity): String {
    if (activityManager(activity).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
        activity.stopLockTask()
    }
    val policy = dpm(activity)
    return if (policy.isDeviceOwnerApp(activity.packageName)) {
        @Suppress("DEPRECATION")
        policy.clearDeviceOwnerApp(activity.packageName)
        "Kiosk exited — device owner relinquished"
    } else {
        "Not device owner (lock task stopped if active)"
    }
}

/** Unwrap ContextWrapper layers so a Compose LocalContext resolves to its hosting Activity. */
fun Context.findActivity(): Activity {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    error("No Activity in context chain")
}
```

- [ ] **Step 4: Run the test, verify it passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.magictablet.kiosk.KioskTest"
```
Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/magictablet/kiosk/Kiosk.kt app/src/test/java/com/magictablet/kiosk/KioskTest.kt
git commit -m "M5 Task 1: kiosk lock-task helpers + shouldStartLockTask guard (TDD)"
```

---

### Task 2: Wire kiosk into MainActivity + manifest + ⚙ exit dialog

**Files:**
- Modify (replace): `app/src/main/java/com/magictablet/MainActivity.kt`
- Modify (replace): `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/com/magictablet/screens/GameScreen.kt`

**Interfaces:**
- Consumes Task 1: `configureLockTask`, `enterLockTaskIfNeeded`, `releaseKiosk`, `findActivity`.

- [ ] **Step 1: Replace `app/src/main/java/com/magictablet/MainActivity.kt`**

```kotlin
package com.magictablet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.magictablet.kiosk.configureLockTask
import com.magictablet.kiosk.enterLockTaskIfNeeded
import com.magictablet.ui.theme.MagicTabletTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureLockTask(this)
        setContent {
            MagicTabletTheme {
                App()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterLockTaskIfNeeded(this)
    }
}
```

- [ ] **Step 2: Replace `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.MagicTablet">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:theme="@style/Theme.MagicTablet">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".AdminReceiver"
            android:description="@string/device_admin_description"
            android:exported="true"
            android:permission="android.permission.BIND_DEVICE_ADMIN">
            <meta-data
                android:name="android.app.device_admin"
                android:resource="@xml/device_admin" />
            <intent-filter>
                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 3: Add the device-admin description string** — in `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="app_name">MTG Table</string>
```
with:
```xml
    <string name="app_name">MTG Table</string>
    <string name="device_admin_description">Enables kiosk (Lock Task) mode so the tablet stays in MTG Table and can be locked for table use.</string>
```

- [ ] **Step 4: Edit `app/src/main/java/com/magictablet/screens/GameScreen.kt`** — five edits:

Edit 4a — remove the two now-unused imports (their only uses are in the helper deleted in Edit 4e; its logic moved to `Kiosk.kt`). Delete these two lines:
```kotlin
import android.app.admin.DevicePolicyManager
import android.content.Context
```
(Keep `import androidx.compose.ui.platform.LocalContext` and the `val context = LocalContext.current` line — the new dialog in Edit 4f reuses that `context`.)

Edit 4b — add imports. Replace:
```kotlin
import androidx.compose.material3.DropdownMenu
```
with:
```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
```
and replace:
```kotlin
import androidx.compose.material3.Text
```
with:
```kotlin
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
```
and replace:
```kotlin
import com.magictablet.rules.CrReader
```
with:
```kotlin
import com.magictablet.kiosk.findActivity
import com.magictablet.kiosk.releaseKiosk
import com.magictablet.rules.CrReader
```

Edit 4c — add the dialog state. Replace:
```kotlin
    var showRules by remember { mutableStateOf(false) }
```
with:
```kotlin
    var showRules by remember { mutableStateOf(false) }
    var showExitKiosk by remember { mutableStateOf(false) }
```

Edit 4d — retarget the menu item. Replace:
```kotlin
                    DropdownMenuItem(text = { Text("Relinquish device owner") }, onClick = { menuOpen = false; relinquishDeviceOwner(context) })
```
with:
```kotlin
                    DropdownMenuItem(text = { Text("Exit kiosk mode") }, onClick = { menuOpen = false; showExitKiosk = true })
```

Edit 4e — delete the old helper function entirely (its logic now lives in `Kiosk.releaseKiosk`). Remove this whole block:
```kotlin
private fun relinquishDeviceOwner(context: Context) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val pkg = context.packageName
    val message = if (dpm.isDeviceOwnerApp(pkg)) {
        @Suppress("DEPRECATION")
        dpm.clearDeviceOwnerApp(pkg)
        "Device owner relinquished"
    } else {
        "Not device owner"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
```

Edit 4f — host the confirm dialog inline (it reuses the outer `context` val from `GameScreen`, so no separate composable is needed). Replace:
```kotlin
    if (showRules) {
        CrReader(viewModel = crViewModel, onClose = { showRules = false })
    }
}
```
with:
```kotlin
    if (showRules) {
        CrReader(viewModel = crViewModel, onClose = { showRules = false })
    }
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
}
```

- [ ] **Step 5: Build + install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
./gradlew :app:installDebug
"$ADB" -s TK12110626B081745 shell am start -n com.magictablet/.MainActivity
```
Expected: BUILD SUCCESSFUL; the app launches. On a **non-device-owner** state it must run normally (NOT enter Lock Task, NOT crash) — confirm the app is usable and take a screenshot of the Game screen. Then open ⚙ and confirm the menu now reads **"Exit kiosk mode"** and (tapping it) shows the confirmation dialog; tap **Cancel** (do not relinquish yet).

- [ ] **Step 6: On-device kiosk lifecycle (spec §7 — the real gate)**

```bash
ADB="/Users/kasonsuchow/Library/Android/sdk/platform-tools/adb"
"$ADB" -s TK12110626B081745 shell dpm set-device-owner com.magictablet/.AdminReceiver
```
Expected: `Success: Device owner set ...`. Then:
- Relaunch the app; confirm it **enters Lock Task** — HOME/recents/back cannot leave MTG Table (try them; `"$ADB" -s TK12110626B081745 shell dumpsys activity | grep -i lockTask` shows the lock-task package/state). Screenshot.
- Confirm the **screen stays on** (leave it a couple minutes, or verify via `"$ADB" -s TK12110626B081745 shell dumpsys power | grep -i "Wake Locks\|mWakefulness"` while foreground).
- **Reboot** (`"$ADB" -s TK12110626B081745 reboot`); after it comes up, confirm it **boots straight into MTG Table** (HOME), still locked, screen on.
- ⚙ → **Exit kiosk mode** → **Exit kiosk**: confirm the toast, that the app leaves Lock Task, and that `"$ADB" -s TK12110626B081745 shell dpm list-owners` (or `dumpsys device_policy`) shows **no device owner**. The tablet is navigable again.

Report exactly what you observed at each step. If anything wedges, the external escape is `"$ADB" -s TK12110626B081745 shell dpm remove-active-admin com.magictablet/.AdminReceiver`.

- [ ] **Step 7: Confirm the unit suite still passes**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "M5 Task 2: wire Lock Task + HOME launcher + keep-screen-on + confirm-guarded kiosk exit"
```

---

## Definition of Done (M5)

- [ ] As Device Owner, the app enters Lock Task on launch and can't be left; a non-owner dev build is unaffected (no lock, no crash).
- [ ] `MainActivity` is registered as HOME (+DEFAULT) — the device boots straight into the app.
- [ ] The screen stays on while the app is foreground.
- [ ] ⚙ "Exit kiosk mode" confirms, then stops Lock Task + relinquishes Device Owner.
- [ ] `allowBackup=false`; the device-admin has a real description string.
- [ ] `KioskTest` passes; the on-device kiosk lifecycle (enter → reboot-into-app → screen-on → guarded exit) is verified.

## Self-Review notes

- **Spec coverage:** the kiosk unit + pure guard + unit test (T1); MainActivity keep-on/lock-task lifecycle, manifest HOME/`allowBackup=false`/description, strings, and the confirm-guarded exit (T2). Every DoD item maps to a task; the external `adb` runbook (spec §8) is exercised in T2 Step 6, not coded.
- **Type consistency:** `shouldStartLockTask(Boolean, Boolean, Int)`, `configureLockTask/enterLockTaskIfNeeded/releaseKiosk(Activity)`, `Context.findActivity(): Activity`, `releaseKiosk` returns `String` — used consistently across MainActivity + GameScreen.
- **Guard correctness:** every privileged call re-checks `isDeviceOwnerApp` live; `startLockTask` gated by `shouldStartLockTask`; `releaseKiosk` stops Lock Task only when in it. Dev builds never lock.
- **Known risks:** the on-device pass locks the tablet — the ⚙ confirm-exit and `adb dpm remove-active-admin` / `pm enable <launcher>` are the escapes. Always-on battery accepted per spec §3. `clearDeviceOwnerApp` deprecated-but-retained.
