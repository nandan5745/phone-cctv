// NetDiag.kt: core (no android.*): phone IP formatting and the likely cause of a Telegram outage;
// used by Bot (outage summary) and CameraService (/status, back-online). Tests: NetDiagTest.
// Docs: docs/INCIDENTS.md O1, docs/ARCHITECTURE.md.

package com.nandan.cctv.core

/** Android's WifiInfo.getIpAddress() packs the address little-endian: first octet in the lowest byte. */
fun formatIpv4(littleEndian: Int): String =
    (0 until 4).joinToString(".") { ((littleEndian ushr (8 * it)) and 0xFF).toString() }

enum class OutageCause(val text: String) {
    WIFI_DOWN("Wi-Fi not connected"),
    CAPTIVE_PORTAL("Wi-Fi login page"),
    NO_INTERNET("no internet"),
    TELEGRAM_DOWN("Telegram unreachable, internet fine"),
}

/**
 * Why Telegram can't be reached. [probeHttpCode] is the answer to a plain-HTTP request to
 * a "generate_204" check page, or null if there was no answer at all.
 */
fun classify(wifiConnected: Boolean, probeHttpCode: Int?): OutageCause = when {
    !wifiConnected -> OutageCause.WIFI_DOWN
    probeHttpCode == null -> OutageCause.NO_INTERNET
    probeHttpCode == 204 -> OutageCause.TELEGRAM_DOWN
    else -> OutageCause.CAPTIVE_PORTAL // 200 / 302 …: something answered in place of the check page
}
