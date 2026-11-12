// SendPolicy.kt: core (no android.*): after a failed Telegram send, retry or give up. Used by Bot.deliver.
// Tests: SendPolicyTest. Docs: docs/INCIDENTS.md S10 (retry until delivered), S16 (lane blocked).

package com.nandan.cctv.core

enum class SendOutcome { RETRY, DROP }

/**
 * Network trouble (no HTTP answer), Telegram busy (429) or broken (5xx) is worth retrying until it
 * gets through. Anything Telegram rejects (other 4xx) or a file that no longer exists will fail the
 * same way forever, and would block its whole send lane, so it is dropped.
 */
fun afterSendFailure(httpCode: Int?, fileMissing: Boolean): SendOutcome = when {
    fileMissing -> SendOutcome.DROP
    httpCode == null || httpCode == 429 || httpCode >= 500 -> SendOutcome.RETRY
    httpCode in 400..499 -> SendOutcome.DROP
    else -> SendOutcome.RETRY
}

/**
 * A motion alert is one queue item; its recipients are read when it is sent (owner + current
 * members), so revoked users get nothing afterwards and the queue holds the same number of alerts
 * whatever the number of users. On a retry, users who already got it are skipped.
 */
fun stillToSend(current: List<Long>, delivered: Set<Long>): List<Long> = current.filter { it !in delivered }
