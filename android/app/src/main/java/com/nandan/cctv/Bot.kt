// Bot.kt: Telegram bot: tg-poll (long polling, commands), tg-text and tg-media send lanes with
// retry-until-delivered, outage summary (core/OutageTracker) with its likely cause (core/NetDiag),
// late commands (core/LateCommands), roles: owner + approved members in private chats (core/Auth,
// core/Commands, core/Routing, core/RateLimiter; members saved in filesDir/users.json).
// Calls CameraService through Bot.Control. Docs: docs/ARCHITECTURE.md §3, §7; incidents S8–S10.

package com.nandan.cctv

import com.nandan.cctv.core.Auth
import com.nandan.cctv.core.Commands
import com.nandan.cctv.core.LateAction
import com.nandan.cctv.core.LateCommands
import com.nandan.cctv.core.OutageCause
import com.nandan.cctv.core.OutageTracker
import com.nandan.cctv.core.RateLimiter
import com.nandan.cctv.core.Role
import com.nandan.cctv.core.Route
import com.nandan.cctv.core.Routing
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
 * The Telegram bot. Three background threads:
 *  - "tg-poll" asks Telegram for new messages (long polling: each request waits up
 *    to 50 s for a message, so there is no open port and no public URL).
 *  - "tg-text" and "tg-media" send messages / photos and videos one at a time, retrying
 *    with a growing delay if the network is down.
 *
 * The owner (config.telegramUserId) and members the owner added with /allow are answered,
 * in private chats only. Members get motion alerts and the viewing commands; strangers get
 * no reply (the owner gets one notice a day per stranger).
 */
class Bot(private val control: Control, private val usersFile: File) {

    /** What the bot can ask the camera service to do. All calls return immediately. */
    interface Control {
        fun snapshot(chatId: Long)
        fun clip(seconds: Int, chatId: Long)
        fun status(chatId: Long)
        fun setRecording(on: Boolean)
        fun setAlerts(on: Boolean)
        fun setVideos(on: Boolean)
        fun last(chatId: Long)
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
            "/allow ID, /revoke ID, /users – roommates\n" +
            "/help – this list\n\n" +
            "Testing only:\n" +
            "/testkill – crash the app on purpose (it should come back by itself)\n" +
            "/teststop – stop the camera service (the 1-minute check should restart it)\n" +
            "/testtemp C – pretend the battery is C °C for 3 minutes (/testtemp cpu C for the processor)"
        const val MEMBER_HELP = "CCTV commands:\n" +
            "/snapshot – photo right now\n" +
            "/clip N – record N seconds (max 30) and send it\n" +
            "/last – the most recent saved clip\n" +
            "/status – how the camera is doing\n" +
            "/help – this list\n\n" +
            "You also get a photo when motion is seen."
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

    // Who may use the bot (guarded by authLock; rebuilt in configure, saved on /allow and /revoke).
    private val authLock = Any()
    private var auth = Auth(0)
    // Poller thread only: one stranger notice per user a day, 10 commands a minute per user.
    private val strangerNotices = RateLimiter(1, 24 * 3_600_000L)
    private val commandLimit = RateLimiter(10, 60_000)
    private val overLimitLog = RateLimiter(1, 60_000)
    private val strangerNames = LinkedHashMap<Long, String>()

    /** Called by the camera on every motion event (counts the ones seen while offline). */
    fun noteMotion() = synchronized(outage) { outage.noteMotion() }

    val enabled get() = api != null

    /** Apply (new) settings. Starts polling if Telegram is configured. */
    @Synchronized
    fun configure(cfg: Config) {
        config = cfg
        synchronized(authLock) {
            val json = try { if (usersFile.exists()) usersFile.readText() else null } catch (e: Exception) { null }
            auth = Auth.fromJson(cfg.telegramUserId, json)
            FileLog.i("telegram: ${auth.users().size - 1} members")
        }
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

    private val owner get() = config?.telegramUserId ?: 0L

    /** Everyone who gets motion alerts: the owner and every member. */
    fun recipients(): List<Long> = synchronized(authLock) { auth.users().map { it.id } }

    /** To the owner (warnings, back online, summaries). */
    fun send(text: String) = sendTo(owner, text)

    fun sendTo(chatId: Long, text: String) = deliver(textLane, "message", chatId) { a, chat -> a.sendMessage(chat, text) }

    /** Like [send], but the text is built when it actually goes out (e.g. to show the current IP). */
    fun sendFresh(make: () -> String) = deliver(textLane, "message", owner) { a, chat -> a.sendMessage(chat, make()) }

    /** [jpeg] is the raw camera JPEG; it is rotated and time-stamped on the sender thread. */
    fun sendPhoto(chatId: Long, jpeg: ByteArray, rotation: Int, label: String, caption: String) =
        deliver(mediaLane, "photo", chatId) { a, chat -> a.sendPhoto(chat, Photo.upright(jpeg, rotation, label), caption) }

    /** Motion alert photo to every user; rotated once, on the sender thread. */
    fun broadcastPhoto(jpeg: ByteArray, rotation: Int, label: String, caption: String) {
        val upright = lazy { Photo.upright(jpeg, rotation, label) }
        for (id in recipients()) deliver(mediaLane, "photo", id) { a, chat -> a.sendPhoto(chat, upright.value, caption) }
    }

    fun sendVideo(chatId: Long, file: File, caption: String) =
        deliver(mediaLane, "video", chatId, file) { a, chat -> a.sendVideo(chat, file, caption) }

    fun broadcastVideo(file: File, caption: String) {
        for (id in recipients()) sendVideo(id, file, caption)
    }

    private fun deliver(lane: ThreadPoolExecutor, what: String, chat: Long, file: File? = null, block: (TelegramApi, Long) -> Unit) {
        if (api == null || lane.isShutdown || chat == 0L) return
        lane.execute {
            var delay = 5_000L
            var attempt = 0
            while (true) {
                attempt++
                val a = api ?: return@execute
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
        val fromObj = msg.optJSONObject("from")
        val from = fromObj?.optLong("id") ?: 0L
        val chatObj = msg.optJSONObject("chat")
        val chatId = chatObj?.optLong("id") ?: 0L
        val chatType = chatObj?.optString("type", "") ?: ""
        val text = msg.optString("text", "").trim()
        val cmd = Commands.parse(text)
        val name = cmd?.name ?: ""
        val now = System.currentTimeMillis()
        val role = synchronized(authLock) { auth.roleOf(from, chatType) }

        when (Routing.route(role, chatType, name)) {
            Route.IGNORE_NOT_PRIVATE -> {
                FileLog.i("telegram: ignored message from $from: chat type $chatType")
                return
            }
            Route.STRANGER -> {
                FileLog.w("telegram: message from unknown user (id $from)")
                val first = fromObj?.optString("first_name")
                if (strangerNames.size > 100) strangerNames.remove(strangerNames.keys.first())
                strangerNames[from] = (first ?: "").trim().take(40).ifEmpty { "id $from" }
                if (strangerNotices.tryAcquire(from, now)) send(Routing.strangerNotice(first, from))
                return
            }
            else -> {}
        }
        if (!commandLimit.tryAcquire(from, now)) {
            if (overLimitLog.tryAcquire(from, now)) FileLog.w("telegram: user $from over 10 messages a minute, ignoring")
            return
        }
        val reply = { t: String -> sendTo(chatId, t) }
        val who = if (role == Role.OWNER) "" else " from member $from"
        val age = now / 1000 - msg.optLong("date")
        if (cmd == null) {
            if (age > MAX_COMMAND_AGE_S) FileLog.i("telegram: ignored old non-command message (${age}s old)") // no "⏭ Skipped" reply for chat text
            else reply("Unknown command. Send /help")
            return
        }
        // (docs/INCIDENTS.md S9) Sent while the phone was offline? Settings still apply; "do it now" commands don't.
        if (LateCommands.action(name, age, MAX_COMMAND_AGE_S.toLong()) == LateAction.SKIP) {
            FileLog.i("telegram: skipped old command '$text'$who (${age}s old)")
            reply("⏭ Skipped $name: sent ${age / 60} min ago while I was offline.")
            return
        }
        if (Routing.route(role, chatType, name) == Route.NOT_ALLOWED) {
            FileLog.i("telegram: refused '$name'$who (owner only)")
            reply("Only the owner can do that.")
            return
        }
        if (age > MAX_COMMAND_AGE_S) FileLog.i("telegram: running late command '$text'$who (${age}s old)")
        else FileLog.i("telegram: command '$text'$who")
        val arg = cmd.arg
        when (name) {
            "/start", "/help" -> reply(if (role == Role.OWNER) HELP else MEMBER_HELP)
            "/snapshot" -> control.snapshot(chatId)
            "/clip" -> {
                val n = arg?.toIntOrNull() ?: 10
                if (n !in 1..30) reply("Usage: /clip N   (N = 1 to 30 seconds)")
                else { reply("Recording $n s…"); control.clip(n, chatId) }
            }
            "/status" -> control.status(chatId)
            "/record" -> onOff(arg)?.let { control.setRecording(it); reply("Recording on motion: ${if (it) "ON" else "OFF"}") }
                ?: reply("Usage: /record on   or   /record off")
            "/alerts" -> onOff(arg)?.let { control.setAlerts(it); reply("Motion alerts: ${if (it) "ON" else "OFF"}") }
                ?: reply("Usage: /alerts on   or   /alerts off")
            "/videos" -> onOff(arg)?.let { control.setVideos(it); reply("Motion videos: ${if (it) "ON" else "OFF"}") }
                ?: reply("Usage: /videos on   or   /videos off")
            "/last" -> control.last(chatId)
            "/sshd" -> control.sshd()
            "/testkill" -> { reply("Killing the app now. Expect a 'back online' message."); control.testKill() }
            "/teststop" -> { reply("Stopping the service. The 1-minute check should restart it."); control.testStop() }
            "/testtemp" -> {
                val t = TestTemp.parse(arg, cmd.rest.split(Regex("\\s+")).getOrNull(1))
                if (t == null) reply("Usage: /testtemp 51 (battery, 1–100)   or   /testtemp cpu 61 (processor, 1–150)")
                else { reply("Pretending the ${if (t.cpu) "processor" else "battery"} is ${t.celsius} °C for 3 minutes."); control.testTemp(t.celsius, t.cpu) }
            }
            "/log" -> reply(FileLog.tail(30))
            "/reload" -> control.reload()
            "/summary" -> control.summary()
            "/allow" -> allow(arg, reply)
            "/revoke" -> revoke(arg, reply)
            "/users" -> reply(usersText())
            else -> reply("Unknown command. Send /help")
        }
    }

    private fun allow(arg: String?, reply: (String) -> Unit) {
        val id = arg?.toLongOrNull() ?: return reply("Usage: /allow ID   (the id from the 👤 notice)")
        val name = strangerNames[id] ?: "id $id"
        if (!synchronized(authLock) { auth.allow(id, name) }) return reply("Not added: $id is the owner or already a member.")
        saveUsers()
        FileLog.i("telegram: allowed member $id")
        reply("✅ Added $name (id $id). They get motion alerts and can use /snapshot, /clip, /last, /status.")
        sendTo(id, "✅ You can now use this camera. Send /help.")
    }

    private fun revoke(arg: String?, reply: (String) -> Unit) {
        val id = arg?.toLongOrNull() ?: return reply("Usage: /revoke ID   (see /users)")
        if (!synchronized(authLock) { auth.revoke(id) }) return reply("Not removed: $id is the owner or not a member.")
        saveUsers()
        FileLog.i("telegram: revoked member $id")
        reply("Removed id $id. They get no more alerts or answers.")
    }

    private fun usersText(): String = synchronized(authLock) {
        auth.users().joinToString("\n", prefix = "Users:\n") {
            "• ${it.name} (id ${it.id}): ${if (it.role == Role.OWNER) "owner" else "member"}"
        }
    }

    /** Written to a temp file first, so a crash mid-write can't leave a half file (which would mean owner-only). */
    private fun saveUsers() {
        val json = synchronized(authLock) { auth.toJson() }
        try {
            val tmp = File(usersFile.path + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(usersFile)) FileLog.e("telegram: could not save ${usersFile.name}")
        } catch (e: Exception) {
            FileLog.e("telegram: could not save ${usersFile.name}", e)
        }
    }

    private fun onOff(arg: String?) = when (arg) { "on" -> true; "off" -> false; else -> null }
}
