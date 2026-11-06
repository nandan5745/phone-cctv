package com.nandan.cctv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Android sends BOOT_COMPLETED after the phone starts, and MY_PACKAGE_REPLACED to the
 * new version of the app right after an update. Both start the camera service.
 * (A receiver is like an event listener that Android calls even when our app isn't running.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone rebooted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        FileLog.i("receiver: $reason, starting service")
        CameraService.start(ctx, reason)
    }
}
