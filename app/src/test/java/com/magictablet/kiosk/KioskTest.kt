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
