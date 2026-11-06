package com.nandan.cctv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** The only screen. Opening the app once starts the camera service. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, CameraService::class.java))
        val text = TextView(this)
        text.setPadding(40, 40, 40, 40)
        text.textSize = 18f
        text.text = "CCTV is watching for motion.\n\nYou can turn the screen off.\n\n" +
            "Clips: /sdcard/cctv/clips/\nLog: /sdcard/cctv/log.txt\nTest image (every 60 s): /sdcard/cctv/test.jpg"
        setContentView(text)
    }
}
