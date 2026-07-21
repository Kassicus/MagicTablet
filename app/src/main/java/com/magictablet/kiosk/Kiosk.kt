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
