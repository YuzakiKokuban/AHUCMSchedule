package com.yuzakikokuban.ahucm.integration

/** A live WebView renews its claim; abandoned pages must not block later syncs. */
internal object ManagedHostLease {
    const val DURATION_MS = 45_000L
    const val HEARTBEAT_MS = 10_000L
    fun until(now: Long): Long = now + DURATION_MS
    fun active(token: String, until: Long, now: Long): Boolean = token.isNotEmpty() && until > now
}
