package com.nandan.cctv

import android.app.Application

/** Runs once when the app's process starts, before any service or screen. */
class CctvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            FileLog.e("CRASH in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        FileLog.i("process started, version ${packageManager.getPackageInfo(packageName, 0).versionName}")
    }
}
