// ConfigImport.kt: core (no android.*): when /sdcard/cctv/config.json may replace the private config.
// Any app can write to /sdcard and open this app, so after first setup only an owner's /reload imports.
// Used by Config.load. Tests: ConfigImportTest. Docs: docs/SECURITY.md.

package com.nandan.cctv.core

object ConfigImport {
    fun allowed(hasToken: Boolean, fromTelegramReload: Boolean): Boolean = !hasToken || fromTelegramReload
}
