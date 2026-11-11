// MainActivity.kt: The only screen. Opening it starts CameraService with ACTION_OPEN (first start or settings
// reload). Docs: docs/ARCHITECTURE.md §4.

package com.nandan.cctv

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** The only screen. Opening the app once starts the camera service. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraService.start(this, "app opened by hand", CameraService.ACTION_OPEN)
        val text = TextView(this)
        text.setPadding(40, 40, 40, 40)
        text.textSize = 18f
        text.text = "CCTV is watching for motion.\n\nYou can turn the screen off.\n\n" +
            "Clips: /sdcard/cctv/clips/\nLog: /sdcard/cctv/log.txt\nTest image (every 60 s): /sdcard/cctv/test.jpg"
        setContentView(text)
    }
}
