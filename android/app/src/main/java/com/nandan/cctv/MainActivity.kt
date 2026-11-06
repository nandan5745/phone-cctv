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
        text.text = "CCTV service started.\n\nYou can turn the screen off.\n\n" +
            "Log: /sdcard/cctv/log.txt\nTest image: /sdcard/cctv/test.jpg"
        setContentView(text)
    }
}
