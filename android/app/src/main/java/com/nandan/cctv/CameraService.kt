@file:Suppress("DEPRECATION") // the old Camera API is deprecated, but it's the right choice for Android 5

package com.nandan.cctv

import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that keeps the back camera running (also with the screen off),
 * watches for motion, and records a short clip when something moves.
 *
 * Threads, in JS terms: Android has no single event loop for everything. The "main"
 * thread runs UI work. We start our own "camera" thread with its own event loop
 * (HandlerThread + Handler ≈ a private setTimeout queue). Every camera call, camera
 * callback and MediaRecorder callback happens on that thread, so no locking is needed.
 *
 * Recording with the old Camera API: the camera can only be used by one owner at a
 * time. To record we stop our frame callback, camera.unlock() hands it to MediaRecorder,
 * and after the clip camera.reconnect() takes it back and motion detection resumes.
 */
class CameraService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TEST_JPG_EVERY_MS = 60_000L
        private const val WATCHDOG_EVERY_MS = 10_000L
        private const val NO_FRAMES_LIMIT_MS = 15_000L
        private const val STATUS_LOG_EVERY_MS = 10 * 60_000L
        private const val WARMUP_AFTER_START_MS = 5_000L   // auto-exposure settling
        private const val WARMUP_AFTER_CLIP_MS = 1_500L
        private const val MAX_RECORD_FAILURES = 3
        private const val WANT_W = 640
        private const val WANT_H = 480
        private const val VIDEO_BITRATE = 700_000
        private const val VIDEO_FPS = 15
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var camThread: HandlerThread
    private lateinit var cam: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var config: Config
    private val detector = MotionDetector()
    private val fileTime = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    // Camera state: only touched on the camera thread.
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private var textureSurface: Surface? = null
    private var buffers: Array<ByteArray>? = null
    private var callbackEpoch = 0 // bumps each time buffers are (re)attached
    private var width = 0
    private var height = 0
    private var startedAt = 0L
    private var lastFrameAt = 0L
    private var lastTestJpgAt = 0L
    private var lastAnalyzedAt = 0L
    private var warmUntil = 0L
    private var motionStreak = 0
    private var frames = 0L
    private var loggedCapabilities = false

    // Recording state (camera thread).
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordStartedAt = 0L
    private var recordFailures = 0
    private var photoOnly = false        // fallback if MediaRecorder keeps failing
    private var lastLowSpaceLogAt = 0L

    // Numbers for the 10-minute status line (helps tune thresholds).
    private var statAnalyzed = 0
    private var statMaxShare = 0f
    private var statMotions = 0
    private var statClips = 0

    // Fallback: a 1x1 pixel window, used only if the phone gives no frames to an
    // off-screen SurfaceTexture. (Not needed on the LYF Flame so far.)
    @Volatile private var useOverlay = false
    @Volatile private var overlay: SurfaceView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.i("service created")
        startForeground(NOTIFICATION_ID, buildNotification())
        config = Config.load(this)
        Storage.clipsDir.mkdirs()
        Storage.deleteBrokenClips()

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
        cam.post(statusLogger)
    }

    // Called every time someone starts the service, e.g. each time the app is opened.
    // START_STICKY: if Android kills the service for memory, it restarts it later.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startId > 1) cam.post { config = Config.load(this) } // opening the app again = reload settings
        return START_STICKY
    }

    override fun onDestroy() {
        FileLog.i("service destroyed")
        cam.removeCallbacksAndMessages(null)
        cam.post { stopRecording(); stopCamera() }
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
                textureSurface = Surface(t)
                c.setPreviewTexture(t)
            }

            c.setErrorCallback { error, _ ->
                FileLog.e("camera error callback: $error, restarting camera")
                cam.post { stopRecording(); stopCamera(); startCamera() }
            }
            attachFrameCallback(c)
            c.startPreview()
            startedAt = SystemClock.elapsedRealtime()
            lastFrameAt = 0L
            warmUntil = startedAt + WARMUP_AFTER_START_MS
            detector.reset()
            FileLog.i("camera started ${width}x$height, surface=${if (useOverlay) "1px overlay" else "SurfaceTexture"}")
        } catch (e: Exception) {
            FileLog.e("camera start failed", e)
            stopCamera()
        }
    }

    /** Two reused byte buffers, so we don't create garbage on every frame. */
    private fun attachFrameCallback(c: Camera) {
        val bufSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
        val b = buffers?.takeIf { it[0].size == bufSize }
            ?: Array(2) { ByteArray(bufSize) }.also { buffers = it }
        b.forEach { c.addCallbackBuffer(it) }
        callbackEpoch++
        c.setPreviewCallbackWithBuffer(frameCallback)
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
        textureSurface?.release()
        textureSurface = null
        texture?.release()
        texture = null
        FileLog.i("camera stopped")
    }

    private val frameCallback = Camera.PreviewCallback { data, c ->
        if (data == null || recorder != null) return@PreviewCallback
        val now = SystemClock.elapsedRealtime()
        if (frames == 0L) FileLog.i("first frame received")
        frames++
        lastFrameAt = now

        if (now - lastTestJpgAt >= TEST_JPG_EVERY_MS) {
            lastTestJpgAt = now
            saveJpeg(data, File(FileLog.dir, "test.jpg"))
        }

        // Only analyse ~analyzeFps frames per second; skip the rest.
        if (now >= warmUntil && now - lastAnalyzedAt >= 1000L / config.analyzeFps) {
            lastAnalyzedAt = now
            val share = detector.changedShare(data, width, height, config.motionPixelDiff)
            if (share >= 0f) {
                statAnalyzed++
                if (share > statMaxShare) statMaxShare = share
                motionStreak = if (share >= config.motionThreshold) motionStreak + 1 else 0
                if (motionStreak >= config.motionFrames) {
                    motionStreak = 0
                    val epoch = callbackEpoch
                    onMotion(data, share) // may hand the camera to MediaRecorder
                    // If buffers were re-attached (recording started, or failed and resumed),
                    // this buffer is already queued again: don't add it twice.
                    if (recorder != null || epoch != callbackEpoch) return@PreviewCallback
                }
            }
        }
        c.addCallbackBuffer(data) // give the buffer back for the next frame
    }

    // ---------------------------------------------------------------- motion + recording

    private fun onMotion(frame: ByteArray, share: Float) {
        statMotions++
        val now = SystemClock.elapsedRealtime()
        val name = fileTime.format(Date())
        FileLog.i("motion: ${"%.1f".format(share * 100)}% changed -> $name")

        Storage.enforceCap(config.storageCapMB)
        val free = Storage.freeMB()
        if (free < config.minFreeMB) {
            if (now - lastLowSpaceLogAt > 10 * 60_000L) {
                lastLowSpaceLogAt = now
                FileLog.w("only $free MB free (< ${config.minFreeMB}), not recording")
            }
            return
        }

        // Still photo of the moment motion was seen (Phase 3 sends this as the alert).
        saveJpeg(frame, File(Storage.clipsDir, "$name.jpg"))

        if (photoOnly) {
            warmUntil = now + config.clipSeconds * 1000L // at most one photo per clip length
            detector.reset()
        } else {
            startRecording(File(Storage.clipsDir, "$name.mp4"))
        }
    }

    private fun startRecording(file: File) {
        val c = camera ?: return
        val r = MediaRecorder()
        try {
            c.setPreviewCallbackWithBuffer(null) // stop our frames; MediaRecorder needs the camera
            c.unlock()
            r.setCamera(c)
            r.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setVideoFrameRate(VIDEO_FPS)
            r.setVideoSize(width, height)
            r.setVideoEncodingBitRate(VIDEO_BITRATE)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setOutputFile(file.path)
            r.setPreviewDisplay(if (useOverlay) overlay?.holder?.surface else textureSurface)
            r.setMaxDuration(config.clipSeconds * 1000)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) cam.post { stopRecording() }
            }
            r.setOnErrorListener { _, what, extra ->
                FileLog.e("MediaRecorder error what=$what extra=$extra")
                cam.post { stopRecording(failed = true) }
            }
            r.prepare()
            r.start()
            recorder = r
            recordingFile = file
            recordStartedAt = SystemClock.elapsedRealtime()
            // Backup in case the max-duration event never comes.
            cam.postDelayed(recordTimeout, config.clipSeconds * 1000L + 5_000L)
        } catch (e: Exception) {
            FileLog.e("recording start failed", e)
            try { r.release() } catch (ignored: Exception) {}
            file.delete()
            noteRecordFailure()
            resumeAfterRecording()
        }
    }

    private val recordTimeout = Runnable {
        FileLog.w("clip did not end by itself, stopping it")
        stopRecording()
    }

    private fun stopRecording(failed: Boolean = false) {
        val r = recorder ?: return
        recorder = null
        cam.removeCallbacks(recordTimeout)
        var ok = !failed
        try {
            r.stop()
        } catch (e: RuntimeException) {
            ok = false // stop() throws if no video was written
            FileLog.e("MediaRecorder.stop failed: ${e.message}")
        }
        try { r.reset(); r.release() } catch (ignored: Exception) {}

        val f = recordingFile
        recordingFile = null
        val secs = (SystemClock.elapsedRealtime() - recordStartedAt) / 1000
        if (f != null) {
            if (ok && f.length() > 10_000) {
                recordFailures = 0
                statClips++
                FileLog.i("clip saved ${f.name} ${f.length() / 1024} KB ${secs}s")
            } else {
                f.delete()
                noteRecordFailure()
            }
        }
        resumeAfterRecording()
        Storage.enforceCap(config.storageCapMB)
    }

    private fun noteRecordFailure() {
        recordFailures++
        if (recordFailures >= MAX_RECORD_FAILURES && !photoOnly) {
            photoOnly = true
            FileLog.e("recording failed $recordFailures times in a row: switching to photo-only mode")
        }
    }

    /**
     * Take the camera back from MediaRecorder and restart motion detection.
     *
     * On the LYF Flame, reconnect() + startPreview() on the same SurfaceTexture hangs ~6 s
     * and then fails (log of 2026-11-06), while closing and reopening the camera takes
     * ~0.3 s. So we always reopen.
     */
    private fun resumeAfterRecording() {
        camera?.let {
            try { it.reconnect() } catch (e: Exception) { FileLog.w("camera reconnect failed: ${e.message}") }
        }
        stopCamera()
        startCamera()
        if (camera != null) {
            // Exposure is already settled after a clip, so a short warm-up is enough.
            warmUntil = SystemClock.elapsedRealtime() + WARMUP_AFTER_CLIP_MS
        }
    }

    /** NV21 → JPEG, written to a temp file and renamed so readers never see half a file. */
    private fun saveJpeg(nv21: ByteArray, target: File) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use {
                YuvImage(nv21, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), 70, it)
            }
            if (!tmp.renameTo(target)) FileLog.w("rename ${target.name} failed")
        } catch (e: Exception) {
            FileLog.e("saving ${target.name} failed", e)
        }
    }

    // Runs every 10 s on the camera thread. Restarts the camera if frames stop.
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                if (recorder != null) {
                    // No frames while recording is normal.
                } else if (camera == null) {
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

    private val statusLogger = object : Runnable {
        override fun run() {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val (count, mb) = Storage.stats()
            FileLog.i("status: ram free ${mi.availMem / 1_048_576}/${mi.totalMem / 1_048_576} MB" +
                (if (mi.lowMemory) " (LOW)" else "") +
                ", heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576}/${rt.maxMemory() / 1_048_576} MB" +
                " | analysed $statAnalyzed, max change ${"%.1f".format(statMaxShare * 100)}%" +
                ", motions $statMotions, clips $statClips | files $count ($mb MB), disk free ${Storage.freeMB()} MB" +
                " | " + batteryText() +
                (if (photoOnly) " | PHOTO-ONLY" else ""))
            statAnalyzed = 0; statMaxShare = 0f; statMotions = 0; statClips = 0
            cam.postDelayed(this, STATUS_LOG_EVERY_MS)
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
                    cam.post { stopRecording(); stopCamera() }
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

    /**
     * Battery level, charging state and temperature. ACTION_BATTERY_CHANGED is a
     * "sticky" broadcast: registering with a null receiver just returns the latest value.
     */
    private fun batteryText(): String {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "battery ?"
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val state = when (b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "unknown"
        }
        val plug = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> " (AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> " (USB)"
            0 -> " (unplugged)"
            else -> ""
        }
        val tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10.0
        return "battery $pct% $state$plug ${"%.1f".format(tempC)}°C"
    }

    private fun isScreenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isScreenOn

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0)
        return Notification.Builder(this)
            .setContentTitle("CCTV running")
            .setContentText("Watching for motion")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
