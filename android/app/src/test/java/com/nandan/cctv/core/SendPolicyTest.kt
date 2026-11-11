// SendPolicyTest.kt: JVM tests for core/SendPolicy (retry or drop a failed Telegram send). Docs: docs/WORKFLOW.md §2.

package com.nandan.cctv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SendPolicyTest {
    @Test
    fun networkErrorRetries() {
        assertEquals(SendOutcome.RETRY, afterSendFailure(httpCode = null, fileMissing = false))
    }

    @Test
    fun tooManyRequestsRetries() {
        assertEquals(SendOutcome.RETRY, afterSendFailure(429, false))
    }

    @Test
    fun serverErrorRetries() {
        assertEquals(SendOutcome.RETRY, afterSendFailure(500, false))
        assertEquals(SendOutcome.RETRY, afterSendFailure(502, false))
    }

    @Test
    fun otherClientErrorDropped() {
        for (code in listOf(400, 403, 404, 413)) assertEquals("$code", SendOutcome.DROP, afterSendFailure(code, false))
    }

    @Test
    fun missingFileDropped() {
        assertEquals(SendOutcome.DROP, afterSendFailure(null, fileMissing = true))
    }
}
