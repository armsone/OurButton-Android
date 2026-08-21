package com.armsone.button

import android.app.Application
import com.armsone.button.push.FirebaseConfiguration

class ButtonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Also runs for an FCM cold start, before any Activity exists. Missing optional client
        // identifiers intentionally leaves Firebase disabled without affecting Bluetooth mode.
        FirebaseConfiguration.load(this).ensureApp(this)
    }
}
