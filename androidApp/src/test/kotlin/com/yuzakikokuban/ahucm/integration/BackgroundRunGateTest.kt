package com.yuzakikokuban.ahucm.integration

import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundRunGateTest {
    @Test fun alreadyRunningStartupDeadlineCannotRetireAStartedSession() {
        val tasks = mutableListOf<Runnable>()
        val future = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ScheduledFuture::class.java)) { _, method, _ ->
            when (method.name) { "cancel", "isCancelled", "isDone" -> false; "getDelay" -> 0L; "compareTo" -> 0; else -> null }
        }
        val scheduler = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ScheduledExecutorService::class.java)) { _, method, args ->
            if (method.name == "schedule") {
                val task = args[0]
                tasks += Runnable { if (task is Runnable) task.run() else (task as Callable<*>).call() }
                future
            } else null
        } as ScheduledExecutorService
        var expired = false
        val gate = BackgroundRunGate(scheduler)
        val ticket = checkNotNull(gate.acquire { expired = true })
        assertTrue(gate.started(ticket))
        tasks.single().run() // cancel(false) cannot prevent a callback already executing.
        assertTrue(gate.owns(ticket))
        assertFalse(expired)
        gate.release(ticket)
    }

    @Test fun blockedStartupExpiresAndLateCompletionCannotReleaseItsSuccessor() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        val unblock = CountDownLatch(1)
        val expired = CountDownLatch(1)
        val gate = BackgroundRunGate(scheduler, 80)
        val first = checkNotNull(gate.acquire { expired.countDown() })
        worker.execute { unblock.await() }
        try {
            assertNull(gate.acquire {})
            assertTrue("Startup RPC must have a watchdog before a Session exists", expired.await(1, TimeUnit.SECONDS))
            val next = checkNotNull(gate.acquire {})
            assertFalse(gate.started(first))
            assertFalse(gate.release(first))
            assertTrue(gate.owns(next))
            assertTrue(gate.started(next))
            assertTrue(gate.release(next))
        } finally { unblock.countDown(); worker.shutdownNow(); scheduler.shutdownNow() }
    }
}
