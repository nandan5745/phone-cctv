// ClipSlot.kt: core (no android.*): one /clip request at a time; a second asker is told to wait instead of
// silently taking over the first one's clip. Used by CameraService (Bot calls it on tg-poll). Tests: ClipSlotTest.

package com.nandan.cctv.core

class ClipSlot {
    data class Request(val chatId: Long, val seconds: Int)

    private var pending: Request? = null

    /** False if another /clip is still waiting to start. */
    @Synchronized
    fun request(chatId: Long, seconds: Int): Boolean {
        if (pending != null) return false
        pending = Request(chatId, seconds)
        return true
    }

    /** The waiting request, if any; frees the slot. */
    @Synchronized
    fun take(): Request? = pending.also { pending = null }
}
