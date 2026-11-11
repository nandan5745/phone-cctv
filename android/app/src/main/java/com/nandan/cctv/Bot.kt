// Bot.kt: Telegram bot: tg-poll (long polling, commands), tg-text and tg-media send lanes with
// retry-until-delivered, outage summary (core/OutageTracker) with its likely cause (core/NetDiag),
// late commands (core/LateCommands).
// Calls CameraService through Bot.Control. Docs: docs/ARCHITECTURE.md §3, §7; incidents S8–S10.

package com.nandan.cctv

import com.nandan.cctv.core.LateAction
import com.nandan.cctv.core.LateCommands
import com.nandan.cctv.core.OutageCause
import com.nandan.cctv.core.OutageTracker
import com.nandan.cctv.core.SendOutcome
import com.nandan.cctv.core.TestTemp
import com.nandan.cctv.core.afterSendFailure
import com.nandan.cctv.core.classify
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
        /** True if the phone is joined to a Wi-Fi network and has an address. */
        fun wifiConnected(): Boolean
        /** Send the daily summary now (counters are not reset). */
        fun summary()
        /** An outage of [minutes] just ended (for the daily summary). */
        fun noteOffline(minutes: Long)
    }

    companion object {
        private const val POLL_TIMEOUT_S = 50
        private const val MAX_COMMAND_AGE_S = 120
        private const val PROBE_AFTER_MS = 120_000L
        private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
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
            "/summary – counts since the last daily summary, storage\n" +
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

    // (docs/INCIDENTS.md S8) Two send lanes, each one thread with at most 20 waiting items (oldest dropped
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
    // Likely cause of the current outage, found once after PROBE_AFTER_MS offline (poller thread only).
    private var outageCause: OutageCause? = null

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

    // (docs/INCIDENTS.md S10) Everything is retried until it gets through (outages lasted hours on 2026-11-08,
    // and alerts were lost after 8 tries). Delays grow 5 s, 10 s, … up to 60 s.

    fun send(text: String) = deliver(textLane, "message") { a, chat -> a.sendMessage(chat, text) }

    /** Like [send], but the text is built when it actually goes out (e.g. to show the current IP). */
    fun sendFresh(make: () -> String) = deliver(textLane, "message") { a, chat -> a.sendMessage(chat, make()) }

    /** [jpeg] is the raw camera JPEG; it is rotated and time-stamped on the sender thread. */
    fun sendPhoto(jpeg: ByteArray, rotation: Int, label: String, caption: String) =
        deliver(mediaLane, "photo") { a, chat -> a.sendPhoto(chat, Photo.upright(jpeg, rotation, label), caption) }

    fun sendVideo(file: File, caption: String) =
        deliver(mediaLane, "video", file) { a, chat -> a.sendVideo(chat, file, caption) }

    private fun deliver(lane: ThreadPoolExecutor, what: String, file: File? = null, block: (TelegramApi, Long) -> Unit) {
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
                    // (docs/INCIDENTS.md S16) An error that can never succeed would block the lane forever.
                    val missing = file != null && !file.exists()
                    if (afterSendFailure((e as? TelegramHttpException)?.code, missing) == SendOutcome.DROP) {
                        FileLog.w("telegram: dropped $what: ${if (missing) "file no longer exists" else "rejected by Telegram"}")
                        return@execute
                    }
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
                val cause = outageCause?.text
                outageCause = null
                synchronized(outage) { outage.onSuccess(System.currentTimeMillis()) }?.let { o ->
                    val min = (o.endMs - o.startMs) / 60_000
                    control.noteOffline(min)
                    FileLog.i("telegram: outage ${clock.format(Date(o.startMs))}-${clock.format(Date(o.endMs))}, ${o.motions} motions" +
                        (cause?.let { ", cause: $it" } ?: ""))
                    send("📴 Was offline ${clock.format(Date(o.startMs))}–${clock.format(Date(o.endMs))} ($min min" +
                        (cause?.let { ", cause: $it" } ?: "") + "), ${o.motions} motions while offline")
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
                val offlineMs = synchronized(outage) {
                    outage.onFailure(System.currentTimeMillis())
                    outage.offlineMs(System.currentTimeMillis())
                }
                failures++
                if (failures == 1 || failures % 30 == 0) FileLog.w("telegram: poll failed ($failures): ${a.safe(e)}")
                if (outageCause == null && offlineMs >= PROBE_AFTER_MS) outageCause = diagnose()
                try { Thread.sleep(backoff) } catch (ie: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
        FileLog.i("telegram: polling stopped")
    }

    /** Once per outage: is it Wi-Fi, a login page, the internet, or only Telegram? (docs/INCIDENTS.md O1) */
    private fun diagnose(): OutageCause {
        val wifi = try { control.wifiConnected() } catch (e: Exception) { false }
        val code = probe()
        val cause = classify(wifi, code)
        FileLog.w("net: outage cause: ${cause.text} (wifi ${if (wifi) "connected" else "not connected"}, check page ${code ?: "no answer"})")
        return cause
    }

    /** HTTP status of the check page, or null if nothing answered (the error is logged: DNS vs timeout). */
    private fun probe(): Int? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(PROBE_URL).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.responseCode
        } catch (e: Exception) {
            FileLog.w("net: check page failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            c?.disconnect()
        }
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
        if (!LateCommands.isCommand(cmd) && age > MAX_COMMAND_AGE_S) {
            FileLog.i("telegram: ignored old non-command message (${age}s old)") // no "⏭ Skipped" reply for chat text
            return
        }
        // (docs/INCIDENTS.md S9) Sent while the phone was offline? Settings still apply; "do it now" commands don't.
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
                val t = TestTemp.parse(arg, parts.getOrNull(2))
                if (t == null) send("Usage: /testtemp 51 (battery, 1–100)   or   /testtemp cpu 61 (processor, 1–150)")
                else { send("Pretending the ${if (t.cpu) "processor" else "battery"} is ${t.celsius} °C for 3 minutes."); control.testTemp(t.celsius, t.cpu) }
            }
            "/log" -> send(FileLog.tail(30))
            "/reload" -> control.reload()
            "/summary" -> control.summary()
            else -> send("Unknown command. Send /help")
        }
    }

    private fun onOff(arg: String?) = when (arg) { "on" -> true; "off" -> false; else -> null }
}
