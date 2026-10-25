package com.yuzakikokuban.ahucm.integration

import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundSessionLifetimeTest {
    @Test fun blockedHeartbeatCannotBlockTheWatchdog() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val worker = Executors.newSingleThreadExecutor()
        val heartbeatStarted = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val timedOut = CountDownLatch(1)
        var reason: String? = null
        val lifetime = BackgroundSessionLifetime(scheduler, worker, {
            heartbeatStarted.countDown(); unblock.await()
        }, { reason = it; timedOut.countDown() }, heartbeatMillis = 10, timeoutMillis = 60)
        try {
            assertTrue(heartbeatStarted.await(2, TimeUnit.SECONDS))
            assertTrue("Watchdog must run independently of a blocked Binder heartbeat", timedOut.await(1, TimeUnit.SECONDS))
            assertEquals("background_sync_timeout", reason)
            assertTrue(lifetime.finish())
            assertFalse(lifetime.finish())
        } finally { unblock.countDown(); lifetime.finish(); worker.shutdownNow(); scheduler.shutdownNow() }
    }
}
