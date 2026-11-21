package com.wakr.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.wakr.app.alarm.Notifications
import com.wakr.app.overlay.OverlayManager
import com.wakr.app.ui.ClockApp

class MainActivity : ComponentActivity() {

    /**
     * Which tab to open on.
     *
     * Tapping a timer or stopwatch notification should land on that tab, not on Alarms. The
     * activity is `singleTask`, so a second tap while it is already open arrives at [onNewIntent]
     * rather than through `onCreate`. Writing to state that the composition reads covers both.
     */
    private var startTab by mutableStateOf<String?>(null)
    private var navigationRequestId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val foldable = com.wakr.app.overlay.IslandDisplayMonitor.state.value.foldable
        if (lockSlabPortrait(foldable, resources.configuration.smallestScreenWidthDp)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        startTab = intent?.getStringExtra(Notifications.EXTRA_TAB)
        navigationRequestId = savedInstanceState?.getLong("navigationRequestId") ?: 0L
        // Edge-to-edge is enabled inside ClockTheme, where the effective dark/light state is known
        // and the system-bar icon contrast can be set to match it.
        setContent { ClockApp(startTab = startTab, startRequestId = navigationRequestId) }
    }

    override fun onPostResume() {
        super.onPostResume()
        OverlayManager.onClockActivityForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startTab = intent.getStringExtra(Notifications.EXTRA_TAB)
        navigationRequestId++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("navigationRequestId", navigationRequestId)
    }
}

internal fun lockSlabPortrait(foldable: Boolean, smallestWidthDp: Int): Boolean = !foldable && smallestWidthDp < 600
