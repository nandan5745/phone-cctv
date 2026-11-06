@file:Suppress("DEPRECATION") // the old Camera API is deprecated, but it's the right choice for Android 5

package com.nandan.cctv

import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 1: a foreground service that keeps the back camera running (also with the
 * screen off) and saves the current frame to /sdcard/cctv/test.jpg every 10 seconds.
 *
 * Threads, in JS terms: Android has no single event loop for everything. The "main"
 * thread runs UI work. We start our own "camera" thread with its own event loop
 * (HandlerThread + Handler ≈ a private setTimeout queue). Every camera call and every
 * camera callback happens on that thread, so the camera state needs no locking.
 */
class CameraService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val SAVE_EVERY_MS = 10_000L
        private const val WATCHDOG_EVERY_MS = 10_000L
        private const val NO_FRAMES_LIMIT_MS = 15_000L
        private const val MEMORY_LOG_EVERY_MS = 10 * 60_000L
        private const val WANT_W = 640
        private const val WANT_H = 480
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var camThread: HandlerThread
    private lateinit var cam: Handler
    private var wakeLock: PowerManager.WakeLock? = null

    // Camera state: only touched on the camera thread.
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var startedAt = 0L
    private var lastFrameAt = 0L
    private var lastSavedAt = 0L
    private var frames = 0L
    private var saves = 0L
    private var loggedCapabilities = false

    // Fallback: a 1x1 pixel window, used only if the phone gives no frames to an
    // off-screen SurfaceTexture.
    @Volatile private var useOverlay = false
    @Volatile private var overlay: SurfaceView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.i("service created")
        startForeground(NOTIFICATION_ID, buildNotification())

        // Keep the CPU running while the screen is off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctv:camera").apply {
            setReferenceCounted(false)
            acquire()
        }

        camThread = HandlerThread("camera").apply { start() }
        cam = Handler(camThread.looper)
        cam.post { startCamera() }
        cam.postDelayed(watchdog, WATCHDOG_EVERY_MS)
        cam.post(memoryLogger)
    }

    // START_STICKY: if Android kills the service for memory, it restarts it later.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        FileLog.i("service destroyed")
        cam.removeCallbacksAndMessages(null)
        cam.post { stopCamera() }
        camThread.quitSafely()
        main.post { removeOverlay() }
        wakeLock?.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        if (camera != null) return
        val holder = overlay?.holder
        if (useOverlay && (holder == null || !holder.surface.isValid)) {
            FileLog.w("overlay surface not ready yet, waiting")
            return
        }
        try {
            val id = backCameraId()
            val c = Camera.open(id)
            camera = c
            val p = c.parameters
            if (!loggedCapabilities) {
                loggedCapabilities = true
                FileLog.i("cameras=${Camera.getNumberOfCameras()} using id=$id")
                FileLog.i("preview sizes: " + p.supportedPreviewSizes.joinToString { "${it.width}x${it.height}" })
                FileLog.i("video sizes: " + (p.supportedVideoSizes?.joinToString { "${it.width}x${it.height}" } ?: "same as preview"))
                FileLog.i("preview formats: ${p.supportedPreviewFormats}  fps ranges: " +
                    p.supportedPreviewFpsRange.joinToString { "${it[0] / 1000}-${it[1] / 1000}" })
                FileLog.i("focus modes: ${p.supportedFocusModes}")
            }
            val size = choosePreviewSize(p.supportedPreviewSizes)
            width = size.width
            height = size.height
            p.setPreviewSize(width, height)
            p.previewFormat = ImageFormat.NV21
            if (p.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            c.parameters = p

            if (useOverlay) {
                c.setPreviewDisplay(holder)
            } else {
                // An off-screen texture: the camera needs *some* surface to draw into.
                val t = SurfaceTexture(10)
                texture = t
                c.setPreviewTexture(t)
            }

            // Two reused byte buffers, so we don't create garbage on every frame.
            val bufSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            repeat(2) { c.addCallbackBuffer(ByteArray(bufSize)) }
            c.setPreviewCallbackWithBuffer(frameCallback)
            c.setErrorCallback { error, _ ->
                FileLog.e("camera error callback: $error, restarting camera")
                cam.post { stopCamera(); startCamera() }
            }
            c.startPreview()
            startedAt = SystemClock.elapsedRealtime()
            lastFrameAt = 0L
            FileLog.i("camera started ${width}x$height, surface=${if (useOverlay) "1px overlay" else "SurfaceTexture"}")
        } catch (e: Exception) {
            FileLog.e("camera start failed", e)
            stopCamera()
        }
    }

    private fun stopCamera() {
        val c = camera ?: return
        camera = null
        try {
            c.setPreviewCallbackWithBuffer(null)
            c.stopPreview()
        } catch (e: Exception) {
            FileLog.w("stopPreview failed", e)
        }
        c.release()
        texture?.release()
        texture = null
        FileLog.i("camera stopped")
    }

    private val frameCallback = Camera.PreviewCallback { data, c ->
        if (data == null) return@PreviewCallback
        val now = SystemClock.elapsedRealtime()
        if (frames == 0L) FileLog.i("first frame received")
        frames++
        lastFrameAt = now
        if (now - lastSavedAt >= SAVE_EVERY_MS) {
            lastSavedAt = now
            saveJpeg(data)
        }
        c.addCallbackBuffer(data) // give the buffer back for the next frame
    }

    /** NV21 → JPEG, written to a temp file and renamed so readers never see half a file. */
    private fun saveJpeg(nv21: ByteArray) {
        try {
            val dir = FileLog.dir.apply { mkdirs() }
            val tmp = File(dir, "test.jpg.tmp")
            FileOutputStream(tmp).use {
                YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), 70, it)
            }
            if (!tmp.renameTo(File(dir, "test.jpg"))) FileLog.w("rename test.jpg failed")
            saves++
            if (saves == 1L || saves % 30 == 0L) {
                FileLog.i("saved test.jpg #$saves, frames so far=$frames, screenOn=${isScreenOn()}")
            }
        } catch (e: Exception) {
            FileLog.e("saving test.jpg failed", e)
        }
    }

    // Runs every 10 s on the camera thread. Restarts the camera if frames stop.
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                if (camera == null) {
                    startCamera()
                } else {
                    val since = if (lastFrameAt == 0L) startedAt else lastFrameAt
                    if (now - since > NO_FRAMES_LIMIT_MS) {
                        FileLog.w("no frames for ${(now - since) / 1000}s (overlay=$useOverlay, screenOn=${isScreenOn()})")
                        stopCamera()
                        if (!useOverlay) {
                            FileLog.w("switching to 1px overlay window fallback")
                            useOverlay = true
                            main.post { addOverlay() } // overlay calls startCamera when its surface is ready
                        } else {
                            startCamera()
                        }
                    }
                }
            } catch (e: Exception) {
                FileLog.e("watchdog error", e)
            }
            cam.postDelayed(this, WATCHDOG_EVERY_MS)
        }
    }

    private val memoryLogger = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            FileLog.i("memory: system free ${mi.availMem / 1_048_576} MB of ${mi.totalMem / 1_048_576} MB" +
                (if (mi.lowMemory) " (LOW)" else "") +
                ", app heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB of max ${rt.maxMemory() / 1_048_576} MB")
            cam.postDelayed(this, MEMORY_LOG_EVERY_MS)
        }
    }

    // ---------------------------------------------------------------- overlay fallback

    private fun addOverlay() {
        if (overlay != null) return
        try {
            val view = SurfaceView(this)
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    FileLog.i("overlay surface created")
                    cam.post { startCamera() }
                }
                override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {
                    FileLog.w("overlay surface destroyed")
                    cam.post { stopCamera() }
                }
            })
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, lp)
            overlay = view
            FileLog.i("overlay window added")
        } catch (e: Exception) {
            FileLog.e("overlay window failed, back to SurfaceTexture", e)
            useOverlay = false
            cam.post { startCamera() }
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            FileLog.w("remove overlay failed", e)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun backCameraId(): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return 0
    }

    /** 640x480 if offered, else the biggest size that fits inside 640x480, else the smallest. */
    private fun choosePreviewSize(sizes: List<Camera.Size>): Camera.Size {
        sizes.firstOrNull { it.width == WANT_W && it.height == WANT_H }?.let { return it }
        sizes.filter { it.width <= WANT_W && it.height <= WANT_H }
            .maxByOrNull { it.width * it.height }?.let { return it }
        return sizes.minByOrNull { it.width * it.height }!!
    }

    private fun isScreenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isScreenOn

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0)
        return Notification.Builder(this)
            .setContentTitle("CCTV running")
            .setContentText("Camera is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
