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

    @Test
    fun recipientsResolvedAtSendTime() {
        // Owner 1, members 2 and 3. 2 already got it; 3 was revoked during the retries; 4 was just added.
        assertEquals(listOf(1L, 4L), stillToSend(current = listOf(1L, 4L), delivered = setOf(2L)))
        assertEquals(emptyList<Long>(), stillToSend(listOf(1L, 2L), setOf(1L, 2L)))
    }
}
