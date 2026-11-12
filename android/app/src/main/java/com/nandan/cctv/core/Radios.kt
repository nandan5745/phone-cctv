// Radios.kt: core (no android.*): the /status line for the phone's radios (Bluetooth should stay off:
// old unpatched Android 5 Bluetooth stack). Used by CameraService. Tests: RadiosTest. Docs: docs/SECURITY.md.

package com.nandan.cctv.core

object Radios {
    /** [bluetoothOn] null = couldn't be read (no adapter or no permission). */
    fun line(bluetoothOn: Boolean?): String = when (bluetoothOn) {
        false -> "Radios: Bluetooth off"
        true -> "Radios: Bluetooth ON (turn it off: docs/SECURITY.md)"
        null -> "Radios: Bluetooth unknown"
    }
}
