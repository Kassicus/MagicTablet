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
