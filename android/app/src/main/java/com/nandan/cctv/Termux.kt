// Termux.kt: Asks Termux (RUN_COMMAND) to run ~/.termux/boot/start-sshd; called by CameraService once
// per boot and by /sshd. Needs allow-external-apps in Termux. Docs: docs/PHONE.md (Termux).

package com.nandan.cctv

import android.content.Context
import android.content.Intent

/**
 * Asks Termux to run ~/.termux/boot/start-sshd (termux-wake-lock + sshd).
 *
 * Termux:Boot can't do this on this phone (boot broadcast is blocked), so the CCTV app
 * does it through Termux's RUN_COMMAND service. For that to work:
 *  - this app holds com.termux.permission.RUN_COMMAND (granted at install on Android 5)
 *  - ~/.termux/termux.properties has: allow-external-apps = true
 * Running it when sshd is already up is harmless: the second sshd can't take the
 * port and exits.
 */
object Termux {
    private const val SCRIPT = "/data/data/com.termux/files/home/.termux/boot/start-sshd"

    fun startSshd(ctx: Context, why: String): Boolean = try {
        val i = Intent("com.termux.RUN_COMMAND")
            .setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", SCRIPT)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        val ok = ctx.startService(i) != null
        FileLog.i("termux: asked to run start-sshd ($why) -> ${if (ok) "sent" else "Termux not found"}")
        ok
    } catch (e: Exception) {
        FileLog.e("termux: could not run start-sshd ($why): ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
