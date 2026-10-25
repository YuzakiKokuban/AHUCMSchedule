package com.yuzakikokuban.ahucm.integration

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Ownership covers startup RPC as well as a running renderer session. */
internal class BackgroundRunGate(private val scheduler: ScheduledExecutorService, private val timeoutMillis: Long = 120_000) {
    class Ticket internal constructor() {
        internal var deadline: ScheduledFuture<*>? = null
        internal var started = false
    }
    private var owner: Ticket? = null
    @Synchronized fun acquire(onTimeout: () -> Unit): Ticket? {
        if (owner != null) return null
        val ticket = Ticket()
        owner = ticket
        ticket.deadline = scheduler.schedule({
            val expired = synchronized(this) {
                if (owner === ticket && !ticket.started) { owner = null; true } else false
            }
            if (expired) onTimeout()
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        return ticket
    }
    @Synchronized fun owns(ticket: Ticket) = owner === ticket
    @Synchronized fun started(ticket: Ticket): Boolean {
        if (owner !== ticket) return false
        ticket.started = true
        ticket.deadline?.cancel(false)
        return true
    }
    @Synchronized fun release(ticket: Ticket): Boolean {
        if (owner !== ticket) return false
        owner = null
        ticket.deadline?.cancel(false)
        return true
    }
}
