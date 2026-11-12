// Termux.kt: Asks Termux (RUN_COMMAND) to run a fixed script in ~/.termux/boot/: start-sshd (/sshd on, or at
// boot if sshdAtBoot) or stop-sshd (/sshd off, auto-off). Needs allow-external-apps in Termux.
// Scripts: phone/termux/. Docs: docs/PHONE.md (Termux), docs/SECURITY.md.

package com.nandan.cctv

import android.content.Context
import android.content.Intent

/**
 * Asks Termux to run one of its own scripts.
 *
 * Termux:Boot can't do this on this phone (boot broadcast is blocked), so the CCTV app
 * does it through Termux's RUN_COMMAND service. For that to work:
 *  - this app holds com.termux.permission.RUN_COMMAND (granted at install on Android 5)
 *  - ~/.termux/termux.properties has: allow-external-apps = true
 * Only the fixed script names below can be run; no user text ever reaches Termux.
 */
object Termux {
    private const val DIR = "/data/data/com.termux/files/home/.termux/boot/"
    const val START_SSHD = "start-sshd"
    const val STOP_SSHD = "stop-sshd"
    private val SCRIPTS = setOf(START_SSHD, STOP_SSHD)

    fun run(ctx: Context, script: String, why: String): Boolean {
        require(script in SCRIPTS) { "unknown script" }
        return try {
            val i = Intent("com.termux.RUN_COMMAND")
                .setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", DIR + script)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            val ok = ctx.startService(i) != null
            FileLog.i("termux: asked to run $script ($why) -> ${if (ok) "sent" else "Termux not found"}")
            ok
        } catch (e: Exception) {
            FileLog.e("termux: could not run $script ($why): ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }
}
