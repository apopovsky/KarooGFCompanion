package com.karoogfcompanion

import android.app.Application
import timber.log.Timber

class GfCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
