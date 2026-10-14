package com.sbro.emucoreh.data.boxart

internal object BoxartRetryPolicy {
    fun nextSyncDelay(retryAtMillis: Long, nowMillis: Long): Long? =
        if (retryAtMillis <= 0L) null else (retryAtMillis - nowMillis).coerceAtLeast(60_000L)
}
