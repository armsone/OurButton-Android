package com.armsone.ourbutton

import android.app.Application
import com.armsone.ourbutton.push.FirebaseConfiguration

class OurButtonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Also runs for an FCM cold start, before any Activity exists. Missing optional client
        // identifiers intentionally leaves Firebase disabled without affecting Bluetooth mode.
        FirebaseConfiguration.load(this).ensureApp(this)
    }
}
