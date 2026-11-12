// ConfigImportTest.kt: JVM tests for core/ConfigImport (when /sdcard/cctv/config.json may be imported).
// Docs: docs/SECURITY.md.

package com.nandan.cctv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigImportTest {
    @Test
    fun firstSetupImports() {
        assertTrue(ConfigImport.allowed(hasToken = false, fromTelegramReload = false))
    }

    @Test
    fun telegramReloadImports() {
        assertTrue(ConfigImport.allowed(hasToken = true, fromTelegramReload = true))
    }

    @Test
    fun appOpenOrOtherStartWithTokenDoesNotImport() {
        // Another app could drop its own config.json and open the app: that must not replace the owner.
        assertFalse(ConfigImport.allowed(hasToken = true, fromTelegramReload = false))
    }
}
