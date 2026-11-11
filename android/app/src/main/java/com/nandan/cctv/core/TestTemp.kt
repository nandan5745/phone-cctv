// TestTemp.kt: core (no android.*): parses "/testtemp C" and "/testtemp cpu C" within what each heat
// monitor accepts (battery ≤ 100 °C, CPU ≤ 150 °C). Used by Bot. Tests: TestTempTest.

package com.nandan.cctv.core

data class TestTemp(val celsius: Double, val cpu: Boolean) {
    companion object {
        /** [arg] = first word after the command, [next] = the second; null if not a usable fake reading. */
        fun parse(arg: String?, next: String?): TestTemp? {
            val cpu = arg == "cpu"
            val c = (if (cpu) next else arg)?.toDoubleOrNull() ?: return null
            val max = if (cpu) 150.0 else 100.0
            return if (c >= 1.0 && c <= max) TestTemp(c, cpu) else null
        }
    }
}
