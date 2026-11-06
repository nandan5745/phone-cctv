package com.nandan.cctv

import java.io.File
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
        fun reload()
        fun testKill()
        fun testStop()
    }

    companion object {
        private const val POLL_TIMEOUT_S = 50
        private const val MAX_SEND_TRIES = 8
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
            "/reload – re-read settings\n" +
            "/help – this list\n\n" +
            "Testing only:\n" +
            "/testkill – crash the app on purpose (it should come back by itself)\n" +
            "/teststop – stop the camera service (the 5-minute check should restart it)"
    }

    @Volatile private var config: Config? = null
    @Volatile private var api: TelegramApi? = null
    @Volatile private var poller: Thread? = null
    private var token = ""

    // One sender thread; at most 20 waiting items, oldest dropped first if the
    // network is down for a long time (keeps memory bounded).
    private val sender = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(20),
        { r -> Thread(r, "tg-send").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

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
        sender.shutdownNow()
    }

    // ------------------------------------------------------------------ sending

    fun send(text: String) = deliver("message") { a, chat -> a.sendMessage(chat, text) }

    /** Keeps retrying until it gets through (e.g. Wi-Fi still connecting after a reboot). */
    fun sendWhenOnline(text: String) =
        deliver("message", maxTries = Int.MAX_VALUE) { a, chat -> a.sendMessage(chat, text) }

    /** [jpeg] is the raw camera JPEG; it is rotated and time-stamped on the sender thread. */
    fun sendPhoto(jpeg: ByteArray, rotation: Int, label: String, caption: String) =
        deliver("photo") { a, chat -> a.sendPhoto(chat, Photo.upright(jpeg, rotation, label), caption) }

    fun sendVideo(file: File, caption: String) =
        deliver("video") { a, chat -> a.sendVideo(chat, file, caption) }

    private fun deliver(what: String, maxTries: Int = MAX_SEND_TRIES, block: (TelegramApi, Long) -> Unit) {
        if (api == null || sender.isShutdown) return
        sender.execute {
            var delay = 5_000L
            var attempt = 0
            while (attempt < maxTries) {
                attempt++
                val a = api ?: return@execute
                val chat = config?.telegramUserId ?: return@execute
                try {
                    block(a, chat)
                    return@execute
                } catch (e: Exception) {
                    // Log the first tries, then only every 10th, so a long outage doesn't flood the log.
                    if (attempt <= 3 || attempt % 10 == 0) FileLog.w("telegram: sending $what failed (try $attempt): ${a.safe(e)}")
                }
                try { Thread.sleep(delay) } catch (e: InterruptedException) { return@execute }
                delay = (delay * 2).coerceAtMost(60_000L)
            }
            FileLog.e("telegram: gave up sending $what after $maxTries tries")
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
        val age = System.currentTimeMillis() / 1000 - msg.optLong("date")
        if (age > MAX_COMMAND_AGE_S) {
            // e.g. sent while the phone was off: running an old /clip later would be confusing
            FileLog.i("telegram: skipped old command '$text' (${age}s old)")
            return
        }
        FileLog.i("telegram: command '$text'")
        val parts = text.split(Regex("\\s+"))
        val cmd = parts[0].lowercase(Locale.US).substringBefore('@')
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
            "/testkill" -> { send("Killing the app now. Expect a 'back online' message."); control.testKill() }
            "/teststop" -> { send("Stopping the service. The 5-minute check should restart it."); control.testStop() }
            "/log" -> send(FileLog.tail(30))
            "/reload" -> control.reload()
            else -> send("Unknown command. Send /help")
        }
    }

    private fun onOff(arg: String?) = when (arg) { "on" -> true; "off" -> false; else -> null }
}
