// TelegramApi.kt: Minimal Telegram Bot API client on HttpURLConnection (no libraries); used only by Bot.
// The token is in every URL, so errors go through safe(). Docs: docs/ARCHITECTURE.md §2.

package com.nandan.cctv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Telegram Bot API client using plain HttpURLConnection (like fetch(), but
 * blocking: every call waits for the answer, so it must run on a background thread).
 *
 * The token is part of every URL, so URLs are never logged, and error messages go
 * through [safe] which removes the token.
 */
/** Telegram answered with an error status ([code]); the message never contains the token. */
class TelegramHttpException(val code: Int, message: String) : IOException(message)

class TelegramApi(private val token: String) {

    fun getMe(): JSONObject = postForm("getMe", emptyMap(), 20_000).getJSONObject("result")

    /** Long polling: Telegram holds the request open up to [timeoutSec] until a message arrives. */
    fun getUpdates(offset: Long, timeoutSec: Int): JSONArray =
        postForm(
            "getUpdates",
            mapOf("offset" to offset.toString(), "timeout" to timeoutSec.toString(), "allowed_updates" to "[\"message\"]"),
            (timeoutSec + 15) * 1000
        ).getJSONArray("result")

    fun sendMessage(chatId: Long, text: String) {
        postForm("sendMessage", mapOf("chat_id" to chatId.toString(), "text" to text.take(4000)), 30_000)
    }

    fun sendPhoto(chatId: Long, jpeg: ByteArray, caption: String) {
        postMultipart("sendPhoto", chatId, caption, "photo", "photo.jpg", "image/jpeg", jpeg.size.toLong()) {
            it.write(jpeg)
        }
    }

    fun sendVideo(chatId: Long, file: File, caption: String) {
        postMultipart("sendVideo", chatId, caption, "video", file.name, "video/mp4", file.length()) { out ->
            file.inputStream().use { it.copyTo(out, 16 * 1024) } // streamed, never fully in RAM
        }
    }

    fun safe(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message?.replace(token, "<token>")}"

    // ------------------------------------------------------------------ HTTP

    private fun open(method: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.requestMethod = "POST"
        return c
    }

    private fun postForm(method: String, params: Map<String, String>, readTimeoutMs: Int): JSONObject {
        val body = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }.toByteArray()
        val c = open(method, readTimeoutMs)
        try {
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            return readResult(c, method)
        } finally {
            c.disconnect()
        }
    }

    /** multipart/form-data upload with an exact Content-Length, so the file is streamed. */
    private fun postMultipart(
        method: String, chatId: Long, caption: String,
        field: String, fileName: String, mime: String, fileLength: Long,
        writeFile: (OutputStream) -> Unit,
    ) {
        val boundary = "----cctv${System.currentTimeMillis()}"
        val head = StringBuilder()
            .append(textPart(boundary, "chat_id", chatId.toString()))
            .append(textPart(boundary, "caption", caption))
            .append("--$boundary\r\n")
            .append("Content-Disposition: form-data; name=\"$field\"; filename=\"$fileName\"\r\n")
            .append("Content-Type: $mime\r\n\r\n")
            .toString().toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val c = open(method, 180_000)
        try {
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(head.size + fileLength + tail.size)
            c.outputStream.use { out ->
                out.write(head)
                writeFile(out)
                out.write(tail)
            }
            readResult(c, method)
        } finally {
            c.disconnect()
        }
    }

    private fun textPart(boundary: String, name: String, value: String) =
        "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"

    private fun readResult(c: HttpURLConnection, method: String): JSONObject {
        val code = c.responseCode
        // For 4xx/5xx, inputStream throws an exception whose message contains the URL
        // (and so the token). errorStream doesn't.
        val text = (if (code < 400) c.inputStream else c.errorStream)?.let { readAll(it) } ?: ""
        val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        if (!json.optBoolean("ok")) {
            throw TelegramHttpException(code, "Telegram $method failed: HTTP $code ${json.optString("description")}")
        }
        return json
    }

    private fun readAll(s: InputStream): String = s.use {
        val out = ByteArrayOutputStream()
        it.copyTo(out)
        out.toString("UTF-8")
    }
}
