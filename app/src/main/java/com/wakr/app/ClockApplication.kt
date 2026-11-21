package com.wakr.app

import android.app.Application
import com.wakr.app.overlay.AppLifecycleTracker
import com.wakr.app.overlay.OverlayManager

class ClockApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppLifecycleTracker)
        if (com.wakr.app.alarm.RuntimeAlarms.unlocked(this)) OverlayManager.init(this)
    }
}
