// CameraService.kt: Foreground service, the heart of the app: camera + motion (core/MotionDetector), clips
// (MediaRecorder), storage, battery/CPU heat checks (core/BatteryMonitor, core/HeatMonitor),
// watchdog, 1-minute restart alarm, and the handlers for Telegram commands (Bot.Control).
// Docs: docs/ARCHITECTURE.md §1–§4; quirks: docs/INCIDENTS.md S1, S5–S7.

@file:Suppress("DEPRECATION") // the old Camera API is deprecated, but it's the right choice for Android 5

package com.nandan.cctv

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
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
import com.nandan.cctv.core.BatteryEvent
import com.nandan.cctv.core.BatteryMonitor
import com.nandan.cctv.core.BatterySample
import com.nandan.cctv.core.HeatMonitor
import com.nandan.cctv.core.MotionDetector
import java.io.ByteArrayOutputStream
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
class CameraService : Service(), Bot.Control {

    companion object {
        /** Intent extra: why the service is being started (shown in the "back online" message). */
        const val EXTRA_REASON = "reason"
        /** Intent action used by MainActivity: opening the app reloads the settings. */
        const val ACTION_OPEN = "com.nandan.cctv.OPEN"
        /** True while the service exists in this process (read by BootReceiver). */
        @Volatile var running = false
            private set
        // (docs/INCIDENTS.md S6) 1 minute: the shortest repeat Android 5 allows. This phone does not restart a
        // crashed service by itself (tested 2026-11-07), so this alarm is the only way back.
        private const val ALARM_EVERY_MS = 60_000L

        /** Starts (or pokes) the service. Safe to call when it's already running. */
        fun start(ctx: Context, reason: String, action: String? = null) {
            ctx.startService(Intent(ctx, CameraService::class.java).setAction(action).putExtra(EXTRA_REASON, reason))
        }

        private const val NOTIFICATION_ID = 1
        private const val TEST_JPG_EVERY_MS = 60_000L
        private const val WATCHDOG_EVERY_MS = 10_000L
        private const val NO_FRAMES_LIMIT_MS = 15_000L
        private const val STATUS_LOG_EVERY_MS = 10 * 60_000L
        private const val BATTERY_EVERY_MS = 60_000L
        private const val TEST_TEMP_CHECKS = 3   // /testtemp fakes the temperature for this many checks
        private const val MAX_CLIP_S = 30
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
    private val labelTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private lateinit var bot: Bot
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceStartedAt = SystemClock.elapsedRealtime()

    // On/off switches set from Telegram; saved so they survive restarts.
    @Volatile private var recordOn = true
    @Volatile private var alertsOn = true
    @Volatile private var videosOn = true
    private var greeted = false
    private var lastVideoAt = -1L

    // Telegram requests and alerts (camera thread).
    private var rotation = 0              // degrees to turn camera images upright
    private var snapshotWanted = false
    private var clipWantedSeconds = 0     // > 0: a /clip is waiting to start
    private var sendClipWhenDone = false
    private var lastAlertAt = -1L

    // Battery watcher state (camera thread).
    // Low %, low voltage (this phone's % is unreliable, voltage isn't), charging and
    // temperature warnings live in the tested core class.
    private lateinit var batteryMonitor: BatteryMonitor
    // (docs/INCIDENTS.md S7) The battery reading follows the charger more than real heat (measured 2026-11-08),
    // so the processor sensor is watched too. The camera pauses while either is too hot.
    private lateinit var cpuHeat: HeatMonitor
    private val heatPausedBy = mutableSetOf<String>()
    private val heatPaused get() = heatPausedBy.isNotEmpty()
    private var testTempC = 0.0            // /testtemp override...
    private var testTempChecksLeft = 0     // ...for this many checks
    private var testTempCpu = false        // ...applied to the CPU sensor instead of the battery
    private var cpuZone: File? = null      // /sys/class/thermal/thermal_zoneN of "sensor_arm"
    private var maxTempDay = ""            // date the max temperature belongs to
    private var chargeRefPct = -1         // highest % seen while plugged in...
    private var chargeRefAt = 0L          // ...and when
    private var drainWarnedAt = -1L

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
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        config = Config.load(this)
        batteryMonitor = newBatteryMonitor()
        cpuHeat = newCpuHeat()
        cpuZone = File("/sys/class/thermal").listFiles()
            ?.firstOrNull { File(it, "type").let { t -> t.canRead() && t.readText().trim() == "sensor_arm" } }
        FileLog.i("cpu temperature sensor: ${cpuZone?.name ?: "not found"}")
        Storage.clipsDir.mkdirs()
        Storage.deleteBrokenClips()
        prefs().let {
            recordOn = it.getBoolean("record", true)
            alertsOn = it.getBoolean("alerts", true)
            videosOn = it.getBoolean("videos", true)
        }

        // Keep the CPU running while the screen is off.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cctv:camera").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Keep Wi-Fi awake too, or Telegram can't reach us while the screen is off.
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cctv").apply {
            setReferenceCounted(false)
            acquire()
        }

        bot = Bot(this)
        bot.configure(config)
        scheduleWatchdogAlarm()
        startSshdOncePerBoot()

        camThread = HandlerThread("camera").apply { start() }
        cam = Handler(camThread.looper)
        cam.post { startCamera() }
        cam.postDelayed(watchdog, WATCHDOG_EVERY_MS)
        cam.post(statusLogger)
        cam.post(batteryWatcher)
    }

    // Called every time someone starts the service, e.g. each time the app is opened.
    // START_STICKY: if Android kills the service for memory, it restarts it later.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent == null means Android re-created the service after killing the app (START_STICKY).
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "restarted by Android after the app was killed"
        if (!greeted) {
            greeted = true
            FileLog.i("started: $reason")
            val version = packageManager.getPackageInfo(packageName, 0).versionName
            bot.send("🟢 CCTV back online (v$version)\n" +
                "Why: $reason\n" +
                "Battery ${BatteryInfo.read(this)?.text() ?: "?"}\n" +
                "Recording ${onOff(recordOn)}, alerts ${onOff(alertsOn)}, videos ${onOff(videosOn)}")
        } else if (intent?.action == ACTION_OPEN) {
            reload() // opening the app again = reload settings
        }
        // Other starts (the 1-minute alarm while we're running) need nothing.
        return START_STICKY
    }

    /**
     * Every minute Android's AlarmManager "starts" this service. If it's running,
     * that does nothing. If it died, this brings it back. The alarm lives in the
     * system, not in our app, so it fires even when our process is gone.
     */
    private fun scheduleWatchdogAlarm() {
        val pi = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).putExtra(EXTRA_REASON, "1-minute check found the service stopped"),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + ALARM_EVERY_MS, ALARM_EVERY_MS, pi
        )
    }

    override fun onDestroy() {
        FileLog.i("service destroyed")
        running = false
        cam.removeCallbacksAndMessages(null)
        cam.post { stopRecording(); stopCamera() }
        camThread.quitSafely()
        main.post { removeOverlay() }
        bot.stop()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        if (camera != null) return
        if (heatPaused) return // cooling down; ResumeAfterHeat starts it again
        val holder = overlay?.holder
        if (useOverlay && (holder == null || !holder.surface.isValid)) {
            FileLog.w("overlay surface not ready yet, waiting")
            return
        }
        try {
            val id = backCameraId()
            rotation = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }.orientation
            val c = Camera.open(id)
            camera = c
            val p = c.parameters
            if (!loggedCapabilities) {
                loggedCapabilities = true
                FileLog.i("cameras=${Camera.getNumberOfCameras()} using id=$id, sensor rotation=$rotation°")
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

        if (snapshotWanted) {
            snapshotWanted = false
            val label = labelTime.format(Date())
            encodeJpeg(data)?.let { bot.sendPhoto(it, rotation, label, "📷 $label") }
        }

        if (clipWantedSeconds > 0) {
            val secs = clipWantedSeconds
            clipWantedSeconds = 0
            if (Storage.freeMB() < config.minFreeMB) {
                bot.send("Not enough free storage to record (${Storage.freeMB()} MB free).")
            } else {
                startRecording(File(Storage.clipsDir, fileTime.format(Date()) + ".mp4"), secs, sendWhenDone = true)
                if (recorder != null) return@PreviewCallback // camera now belongs to MediaRecorder
                bot.send("Recording failed, see /log")
                return@PreviewCallback // buffers were re-attached by resumeAfterRecording
            }
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
        bot.noteMotion()
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

        // Still photo of the moment motion was seen; also the Telegram alert.
        val jpeg = saveJpeg(frame, File(Storage.clipsDir, "$name.jpg"))
        val cooldownMs = config.alertCooldownSeconds * 1000L
        if (alertsOn && jpeg != null && (lastAlertAt < 0 || now - lastAlertAt >= cooldownMs)) {
            lastAlertAt = now
            val label = labelTime.format(Date())
            bot.sendPhoto(jpeg, rotation, label, "🚨 Motion at $label (${"%.0f".format(share * 100)}% of view changed)")
        }

        if (photoOnly || !recordOn) {
            warmUntil = now + config.clipSeconds * 1000L // at most one photo per clip length
            detector.reset()
        } else {
            // Send the video too (after the photo), with the same cooldown as alerts.
            val sendVideo = videosOn && (lastVideoAt < 0 || now - lastVideoAt >= cooldownMs)
            if (sendVideo) lastVideoAt = now
            startRecording(File(Storage.clipsDir, "$name.mp4"), config.clipSeconds, sendWhenDone = sendVideo)
        }
    }

    private fun startRecording(file: File, seconds: Int, sendWhenDone: Boolean) {
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
            r.setOrientationHint(rotation) // players show the video upright
            r.setMaxDuration(seconds * 1000)
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
            sendClipWhenDone = sendWhenDone
            // Backup in case the max-duration event never comes.
            cam.postDelayed(recordTimeout, seconds * 1000L + 5_000L)
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
                if (sendClipWhenDone) bot.sendVideo(f, "🎥 ${f.nameWithoutExtension} (${secs}s)")
            } else {
                f.delete()
                noteRecordFailure()
                if (sendClipWhenDone) bot.send("Recording failed, see /log")
            }
        }
        sendClipWhenDone = false
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
     * (docs/INCIDENTS.md S1) On the LYF Flame, reconnect() + startPreview() on the same SurfaceTexture hangs ~6 s
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

    /** NV21 camera frame → JPEG bytes. */
    private fun encodeJpeg(nv21: ByteArray): ByteArray? = try {
        val out = ByteArrayOutputStream(64 * 1024)
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 70, out)
        out.toByteArray()
    } catch (e: Exception) {
        FileLog.e("JPEG encoding failed", e)
        null
    }

    /** Saves a frame as JPEG via a temp file + rename, so readers never see half a file. Returns the bytes. */
    private fun saveJpeg(nv21: ByteArray, target: File): ByteArray? {
        val jpeg = encodeJpeg(nv21) ?: return null
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { it.write(jpeg) }
            if (!tmp.renameTo(target)) FileLog.w("rename ${target.name} failed")
        } catch (e: Exception) {
            FileLog.e("saving ${target.name} failed", e)
        }
        return jpeg
    }

    // Runs every 10 s on the camera thread. Restarts the camera if frames stop.
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                if (recorder != null) {
                    // No frames while recording is normal.
                } else if (camera == null) {
                    startCamera() // does nothing while heatPaused
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
                " | battery " + (BatteryInfo.read(this@CameraService)?.text() ?: "?") +
                (if (photoOnly) " | PHOTO-ONLY" else ""))
            statAnalyzed = 0; statMaxShare = 0f; statMotions = 0; statClips = 0
            cam.postDelayed(this, STATUS_LOG_EVERY_MS)
        }
    }

    /**
     * Every minute: log the battery, and warn on Telegram when it is low, stops
     * charging, or keeps dropping although the phone says it is charging (a charger
     * too weak for camera + Wi-Fi, or a bad cable).
     */
    private val batteryWatcher = object : Runnable {
        override fun run() {
            try {
                checkBattery()
            } catch (e: Exception) {
                FileLog.e("battery check failed", e)
            }
            cam.postDelayed(this, BATTERY_EVERY_MS)
        }
    }

    private fun newBatteryMonitor() = BatteryMonitor(
        warnC = config.tempWarnC, criticalC = config.tempCriticalC,
        emergencyC = config.tempEmergencyC, resumeC = config.tempResumeC,
    )

    private fun newCpuHeat() = HeatMonitor(
        "Phone CPU", config.cpuWarnC, config.cpuCriticalC, config.cpuEmergencyC, config.cpuResumeC, maxValidC = 150.0,
    )

    /** Processor temperature in °C, or null if the sensor isn't readable. */
    private fun cpuTempC(): Double? = try {
        cpuZone?.let { File(it, "temp").readText().trim().toDouble() / 1000.0 }
    } catch (e: Exception) {
        null
    }

    private fun checkBattery() {
        val b = BatteryInfo.read(this) ?: return
        val now = SystemClock.elapsedRealtime()
        var tempC = b.tempC
        var cpuC = cpuTempC()
        if (testTempChecksLeft > 0) {
            testTempChecksLeft--
            if (testTempCpu) cpuC = testTempC else tempC = testTempC
            FileLog.w("testtemp: fake ${if (testTempCpu) "cpu" else "battery"} ${testTempC}°C (${testTempChecksLeft} checks left)")
        }
        FileLog.i("battery: ${b.text()} | cpu ${cpuC?.let { "%.1f".format(it) } ?: "?"}°C")

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (today != maxTempDay) { maxTempDay = today; batteryMonitor.resetMax(); cpuHeat.resetMax() }

        val sample = BatterySample(b.pct, b.charging, tempC, b.voltageMv, b.healthText(), System.currentTimeMillis())
        handleHeatEvents("battery", tempC, batteryMonitor.update(sample), b)
        if (cpuC != null) handleHeatEvents("cpu", cpuC, cpuHeat.update(cpuC), b)

        // "Charging" but the level keeps falling: compare with the best level of the last hour.
        if (!b.charging || b.pct > chargeRefPct || now - chargeRefAt > 60 * 60_000L) {
            chargeRefPct = b.pct
            chargeRefAt = now
        } else if (chargeRefPct - b.pct >= 3 && (drainWarnedAt < 0 || now - drainWarnedAt > 60 * 60_000L)) {
            drainWarnedAt = now
            bot.send("⚠️ Battery is DROPPING while plugged in ($chargeRefPct% → ${b.pct}% in " +
                "${(now - chargeRefAt) / 60_000} min). The charger or cable can't keep up.\n${b.text()}")
        }
    }

    private fun handleHeatEvents(sensor: String, tempC: Double, events: List<BatteryEvent>, b: BatteryInfo) {
        for (e in events) {
            when (e) {
                is BatteryEvent.Notify -> bot.send("${e.text}\n${b.text()}")
                BatteryEvent.PauseForHeat -> {
                    FileLog.w("heat: $sensor ${tempC}°C, pausing camera")
                    heatPausedBy += sensor
                    stopRecording()
                    stopCamera()
                }
                BatteryEvent.ResumeAfterHeat -> {
                    heatPausedBy -= sensor
                    FileLog.i("heat: $sensor cooled to ${tempC}°C${if (heatPaused) ", still paused by $heatPausedBy" else ", resuming camera"}")
                    startCamera() // does nothing while another sensor is still too hot
                }
            }
        }
    }

    private fun statusText(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val (count, mb) = Storage.stats()
        val wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        val bars = WifiManager.calculateSignalLevel(wifi.rssi, 5)
        return "📊 CCTV status\n" +
            "Up: ${duration(SystemClock.elapsedRealtime() - serviceStartedAt)} (phone up ${duration(SystemClock.elapsedRealtime())})\n" +
            "Battery: ${BatteryInfo.read(this)?.text() ?: "?"}\n" +
            "Temp: battery ${BatteryInfo.read(this)?.let { "%.1f".format(it.tempC) } ?: "?"} °C (max today ${"%.1f".format(batteryMonitor.maxTempC)}), " +
            "CPU ${cpuTempC()?.let { "%.1f".format(it) } ?: "?"} °C (max today ${"%.1f".format(cpuHeat.maxC)})\n" +
            "Storage: $count files, $mb MB of ${config.storageCapMB} MB cap, ${Storage.freeMB()} MB free\n" +
            "Recording: ${onOff(recordOn)}${if (photoOnly) " (photo-only fallback)" else ""}, alerts: ${onOff(alertsOn)}, videos: ${onOff(videosOn)}\n" +
            "RAM free: ${mi.availMem / 1_048_576} MB of ${mi.totalMem / 1_048_576} MB\n" +
            "Wi-Fi: ${wifi.rssi} dBm ($bars/4)\n" +
            "Camera: ${if (heatPaused) "PAUSED (too hot)" else if (camera != null || recorder != null) "${width}x$height" else "NOT RUNNING"}${if (recorder != null) ", recording now" else ""}"
    }

    private fun duration(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "${m}m" else if (m < 24 * 60) "${m / 60}h ${m % 60}m" else "${m / 1440}d ${(m % 1440) / 60}h"
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    private fun prefs() = getSharedPreferences("state", Context.MODE_PRIVATE)

    /**
     * After a reboot nothing starts sshd (Termux:Boot is blocked on this phone), so the
     * first service start after each boot asks Termux to do it. "Boot time" = now minus
     * time since boot; it identifies the boot (allowing a minute of clock jitter).
     */
    private fun startSshdOncePerBoot() {
        val bootMin = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
        val last = prefs().getLong("sshdBootMin", 0)
        if (Math.abs(bootMin - last) > 1) {
            if (Termux.startSshd(this, "first start since boot")) prefs().edit().putLong("sshdBootMin", bootMin).apply()
        }
    }

    // ---------------------------------------------------------------- Telegram commands (Bot.Control)
    // Called on the "tg-poll" thread; the work is posted to the camera thread.

    override fun snapshot() { cam.post { snapshotWanted = true } } // taken from the next frame
    override fun clip(seconds: Int) { cam.post { clipWantedSeconds = seconds.coerceIn(1, MAX_CLIP_S) } }
    override fun status() { cam.post { bot.send(statusText()) } }
    override fun setRecording(on: Boolean) { recordOn = on; prefs().edit().putBoolean("record", on).apply() }
    override fun setAlerts(on: Boolean) { alertsOn = on; prefs().edit().putBoolean("alerts", on).apply() }
    override fun sshd() {
        val ok = Termux.startSshd(this, "/sshd command")
        bot.send(if (ok) "Asked Termux to start sshd. Try ssh in ~10 s." else "Could not reach Termux, see /log")
    }

    override fun setVideos(on: Boolean) { videosOn = on; prefs().edit().putBoolean("videos", on).apply() }

    override fun last() {
        cam.post {
            val clip = Storage.clipsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".mp4") && it != recordingFile }
                ?.maxByOrNull { it.name }
            if (clip == null) bot.send("No saved clips yet.")
            else bot.sendVideo(clip, "🎞 Last clip: ${clip.nameWithoutExtension} (${clip.length() / 1024} KB)")
        }
    }

    // Testing helpers for Phase 4.
    override fun testKill() {
        // Like a crash or Android killing us for memory. Wait 2 s so the reply gets out first.
        cam.postDelayed({ FileLog.w("testkill: killing own process"); android.os.Process.killProcess(android.os.Process.myPid()) }, 2_000)
    }

    override fun testTemp(celsius: Double, cpu: Boolean) {
        cam.post {
            testTempC = celsius
            testTempCpu = cpu
            testTempChecksLeft = TEST_TEMP_CHECKS
            checkBattery() // apply now; the next checks run every minute
        }
    }

    override fun testStop() {
        // A clean stop: START_STICKY won't restart this, only the 1-minute alarm will.
        main.postDelayed({ FileLog.w("teststop: stopping service"); stopSelf() }, 2_000)
    }
    override fun reload() {
        cam.post {
            config = Config.load(this)
            bot.configure(config)
            if (!heatPaused) { batteryMonitor = newBatteryMonitor(); cpuHeat = newCpuHeat() } // new thresholds; keep state while paused
            bot.send("Settings reloaded.")
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
