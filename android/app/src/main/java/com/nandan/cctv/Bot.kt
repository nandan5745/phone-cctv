package com.nandan.cctv

import com.nandan.cctv.core.LateAction
import com.nandan.cctv.core.LateCommands
import com.nandan.cctv.core.OutageTracker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Telegram bot. Two background threads:
 *  - "tg-poll" asks Telegram for new messages (long polling: each request waits up
 *    to 50 s for a message, so there is no open port and no public URL).
 *  - "tg-send" sends messages/photos/videos one at a time, retrying with a growing
 *    delay if the network is down.
 *
 * Only messages from config.telegramUserId are answered. Everyone else is ignored
 * without a reply.
 */
class Bot(private val control: Control) {

    /** What the bot can ask the camera service to do. All calls return immediately. */
    interface Control {
        fun snapshot()
        fun clip(seconds: Int)
        fun status()
        fun setRecording(on: Boolean)
        fun setAlerts(on: Boolean)
        fun setVideos(on: Boolean)
        fun last()
        fun sshd()
        fun reload()
        fun testKill()
        fun testStop()
        fun testTemp(celsius: Double, cpu: Boolean)
    }

    companion object {
        private const val POLL_TIMEOUT_S = 50
        private const val MAX_COMMAND_AGE_S = 120
        const val HELP = "CCTV commands:\n" +
            "/snapshot – photo right now\n" +
            "/clip N – record N seconds (max 30) and send it\n" +
            "/status – battery, storage, memory, Wi-Fi\n" +
            "/record on|off – record clips on motion\n" +
            "/alerts on|off – photo alerts on motion\n" +
            "/videos on|off – also send the motion video\n" +
            "/last – send the most recent saved clip\n" +
            "/log – last 30 log lines\n" +
            "/sshd – start SSH on the phone (Termux)\n" +
            "/reload – re-read settings\n" +
            "/help – this list\n\n" +
            "Testing only:\n" +
            "/testkill – crash the app on purpose (it should come back by itself)\n" +
            "/teststop – stop the camera service (the 1-minute check should restart it)\n" +
            "/testtemp C – pretend the battery is C °C for 3 minutes (/testtemp cpu C for the processor)"
    }

    @Volatile private var config: Config? = null
    @Volatile private var api: TelegramApi? = null
    @Volatile private var poller: Thread? = null
    private var token = ""

    // Two send lanes, each one thread with at most 20 waiting items (oldest dropped
    // first, keeps memory bounded). Text has its own lane so a stuck photo/video upload
    // can't delay replies (2026-11-08: a snapshot upload held /status back 14 min).
    private val textLane = lane("tg-text")
    private val mediaLane = lane("tg-media")

    private fun lane(name: String) = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(20),
        { r -> Thread(r, name).apply { isDaemon = true } },
        { r, ex ->
            if (!ex.isShutdown) {
                ex.queue.poll()
                FileLog.w("telegram: $name queue full, dropped oldest")
                ex.execute(r)
            }
        }
    )

    // Telegram unreachable: when, for how long, and motions missed meanwhile.
    private val outage = OutageTracker()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    /** Called by the camera on every motion event (counts the ones seen while offline). */
    fun noteMotion() = synchronized(outage) { outage.noteMotion() }

    val enabled get() = api != null

    /** Apply (new) settings. Starts polling if Telegram is configured. */
    @Synchronized
    fun configure(cfg: Config) {
        config = cfg
        if (cfg.telegramBotToken.isEmpty() || cfg.telegramUserId == 0L) {
            api = null
            FileLog.i("telegram: not configured (need telegramBotToken and telegramUserId)")
            return
        }
        if (api == null || cfg.telegramBotToken != token) {
            token = cfg.telegramBotToken
            api = TelegramApi(token)
        }
        if (poller?.isAlive != true) {
            poller = Thread({ pollLoop() }, "tg-poll").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        api = null
        poller?.interrupt()
        textLane.shutdownNow()
        mediaLane.shutdownNow()
    }

    // ------------------------------------------------------------------ sending

    // Everything is retried until it gets through (outages lasted hours on 2026-11-08,
    // and alerts were lost after 8 tries). Delays grow 5 s, 10 s, … up to 60 s.

    fun send(text: String) = deliver(textLane, "message") { a, chat -> a.sendMessage(chat, text) }

    /** [jpeg] is the raw camera JPEG; it is rotated and time-stamped on the sender thread. */
    fun sendPhoto(jpeg: ByteArray, rotation: Int, label: String, caption: String) =
        deliver(mediaLane, "photo") { a, chat -> a.sendPhoto(chat, Photo.upright(jpeg, rotation, label), caption) }

    fun sendVideo(file: File, caption: String) =
        deliver(mediaLane, "video") { a, chat -> a.sendVideo(chat, file, caption) }

    private fun deliver(lane: ThreadPoolExecutor, what: String, block: (TelegramApi, Long) -> Unit) {
        if (api == null || lane.isShutdown) return
        lane.execute {
            var delay = 5_000L
            var attempt = 0
            while (true) {
                attempt++
                val a = api ?: return@execute
                val chat = config?.telegramUserId ?: return@execute
                val started = System.currentTimeMillis()
                try {
                    block(a, chat)
                    if (attempt > 1) FileLog.i("telegram: sent $what on try $attempt")
                    return@execute
                } catch (e: Exception) {
                    val secs = (System.currentTimeMillis() - started) / 1000
                    FileLog.w("telegram: sending $what failed (try $attempt, ${secs} s): ${a.safe(e)}")
                }
                try { Thread.sleep(delay) } catch (e: InterruptedException) { return@execute }
                delay = (delay * 2).coerceAtMost(60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ receiving

    private fun pollLoop() {
        var offset = 0L
        var failures = 0
        var backoff = 2_000L
        var greeted = false
        while (true) {
            val a = api ?: break
            try {
                if (!greeted) {
                    // First call also proves HTTPS to api.telegram.org works on Android 5.
                    val me = a.getMe()
                    FileLog.i("telegram: connected as @${me.optString("username")}")
                    greeted = true
                }
                val updates = a.getUpdates(offset, POLL_TIMEOUT_S)
                if (failures > 0) {
                    FileLog.i("telegram: reachable again after $failures failed tries")
                    failures = 0
                }
                synchronized(outage) { outage.onSuccess(System.currentTimeMillis()) }?.let { o ->
                    val min = (o.endMs - o.startMs) / 60_000
                    FileLog.i("telegram: outage ${clock.format(Date(o.startMs))}-${clock.format(Date(o.endMs))}, ${o.motions} motions")
                    send("📴 Was offline ${clock.format(Date(o.startMs))}–${clock.format(Date(o.endMs))} ($min min), " +
                        "${o.motions} motions while offline")
                }
                backoff = 2_000L
                for (i in 0 until updates.length()) {
                    val u = updates.getJSONObject(i)
                    offset = u.getLong("update_id") + 1 // tells Telegram we've handled it
                    try {
                        handle(u)
                    } catch (e: Exception) {
                        FileLog.e("telegram: command failed", e)
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                synchronized(outage) { outage.onFailure(System.currentTimeMillis()) }
                failures++
                if (failures == 1 || failures % 30 == 0) FileLog.w("telegram: poll failed ($failures): ${a.safe(e)}")
                try { Thread.sleep(backoff) } catch (ie: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
        FileLog.i("telegram: polling stopped")
    }

    private fun handle(update: org.json.JSONObject) {
        val msg = update.optJSONObject("message") ?: return
        val from = msg.optJSONObject("from")?.optLong("id") ?: 0L
        if (from != config?.telegramUserId) {
            FileLog.w("telegram: ignored message from another user (id $from)")
            return
        }
        val text = msg.optString("text", "").trim()
        val parts = text.split(Regex("\\s+"))
        val cmd = parts[0].lowercase(Locale.US).substringBefore('@')
        val age = System.currentTimeMillis() / 1000 - msg.optLong("date")
        // Sent while the phone was offline? Settings still apply; "do it now" commands don't.
        if (LateCommands.action(cmd, age, MAX_COMMAND_AGE_S.toLong()) == LateAction.SKIP) {
            FileLog.i("telegram: skipped old command '$text' (${age}s old)")
            send("⏭ Skipped $cmd: sent ${age / 60} min ago while I was offline.")
            return
        }
        if (age > MAX_COMMAND_AGE_S) FileLog.i("telegram: running late command '$text' (${age}s old)")
        else FileLog.i("telegram: command '$text'")
        val arg = parts.getOrNull(1)?.lowercase(Locale.US)
        when (cmd) {
            "/start", "/help" -> send(HELP)
            "/snapshot" -> control.snapshot()
            "/clip" -> {
                val n = arg?.toIntOrNull() ?: 10
                if (n !in 1..30) send("Usage: /clip N   (N = 1 to 30 seconds)")
                else { send("Recording $n s…"); control.clip(n) }
            }
            "/status" -> control.status()
            "/record" -> onOff(arg)?.let { control.setRecording(it); send("Recording on motion: ${if (it) "ON" else "OFF"}") }
                ?: send("Usage: /record on   or   /record off")
            "/alerts" -> onOff(arg)?.let { control.setAlerts(it); send("Motion alerts: ${if (it) "ON" else "OFF"}") }
                ?: send("Usage: /alerts on   or   /alerts off")
            "/videos" -> onOff(arg)?.let { control.setVideos(it); send("Motion videos: ${if (it) "ON" else "OFF"}") }
                ?: send("Usage: /videos on   or   /videos off")
            "/last" -> control.last()
            "/sshd" -> control.sshd()
            "/testkill" -> { send("Killing the app now. Expect a 'back online' message."); control.testKill() }
            "/teststop" -> { send("Stopping the service. The 1-minute check should restart it."); control.testStop() }
            "/testtemp" -> {
                val cpu = arg == "cpu"
                val c = (if (cpu) parts.getOrNull(2) else arg)?.toDoubleOrNull()
                if (c == null || c !in 1.0..150.0) send("Usage: /testtemp 51   or   /testtemp cpu 86")
                else { send("Pretending the ${if (cpu) "processor" else "battery"} is $c °C for 3 minutes."); control.testTemp(c, cpu) }
            }
            "/log" -> send(FileLog.tail(30))
            "/reload" -> control.reload()
            else -> send("Unknown command. Send /help")
        }
    }

    private fun onOff(arg: String?) = when (arg) { "on" -> true; "off" -> false; else -> null }
}
