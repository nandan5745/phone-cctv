// BootReceiver.kt: Starts CameraService after boot (via Wi-Fi/network/charger/unlock events, because this phone
// blocks BOOT_COMPLETED) and after an update. Docs: docs/ARCHITECTURE.md §4, docs/INCIDENTS.md S5.

package com.nandan.cctv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Starts the camera service when the phone starts or the app is updated.
 * (A receiver is like an event listener that Android calls even when our app isn't running.)
 *
 * (docs/INCIDENTS.md S5) This phone runs in low-RAM mode (ro.config.low_ram=true) and never delivered
 * BOOT_COMPLETED to us (reboot test, 2026-11-07). So we also listen for other events
 * that happen after every boot (Wi-Fi/network coming up, charger, unlock, storage
 * mounted). Whichever arrives first starts the service. If the service is already
 * running, these events are ignored.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        val uptimeMin = SystemClock.elapsedRealtime() / 60_000
        val reason = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone rebooted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> {
                if (CameraService.running) return // the normal case: nothing to do
                val event = action.substringAfterLast('.')
                if (uptimeMin < 30) "phone rebooted (first signal: $event, ${uptimeMin} min after boot)"
                else "woken by $event (service wasn't running)"
            }
        }
        FileLog.i("receiver: $action (uptime ${uptimeMin} min) -> starting service")
        CameraService.start(ctx, reason)
    }
}
