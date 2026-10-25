package com.yuzakikokuban.ahucm.integration

import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scheduler callbacks must remain independent of host RPC and HTTP calls. */
internal class BackgroundSessionLifetime(
    scheduler: ScheduledExecutorService,
    heartbeatWorker: Executor,
    heartbeat: () -> Unit,
    expire: (String) -> Unit,
    heartbeatMillis: Long = ManagedHostLease.HEARTBEAT_MS,
    timeoutMillis: Long = 120_000
) {
    private val closed = AtomicBoolean(false)
    private val heartbeatRunning = AtomicBoolean(false)
    val finished get() = closed.get()
    private val heartbeatTask = scheduler.scheduleAtFixedRate({
        if (!finished && heartbeatRunning.compareAndSet(false, true)) {
            heartbeatWorker.execute {
                try { if (!finished) heartbeat() }
                catch (_: Exception) { expire("background_lease_lost") }
                finally { heartbeatRunning.set(false) }
            }
        }
    }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS)
    private val timeoutTask = scheduler.schedule({ if (!finished) expire("background_sync_timeout") }, timeoutMillis, TimeUnit.MILLISECONDS)

    fun finish(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        heartbeatTask.cancel(false)
        timeoutTask.cancel(false)
        return true
    }
}
