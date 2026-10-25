package com.yuzakikokuban.ahucm.integration

import org.junit.Assert.*
import org.junit.Test

class ManagedHostLeaseTest {
    @Test fun abandonedPageReleasesItsClaimWithinFortyFiveSeconds() {
        val start = 1_000L
        val lease = ManagedHostLease.until(start)
        assertTrue(ManagedHostLease.active("claim", lease, start + 1))
        assertFalse(ManagedHostLease.active("claim", lease, start + 45_000))
    }

    @Test fun heartbeatsKeepLongRunningJobsAliveAndEmptyTokensNeverBlockTheQueue() {
        val start = 1_000L
        val renewed = ManagedHostLease.until(start + 40_000)
        assertTrue(ManagedHostLease.active("claim", renewed, start + 60_000))
        assertFalse(ManagedHostLease.active("", renewed, start + 60_000))
        assertTrue(ManagedHostLease.HEARTBEAT_MS * 2 < ManagedHostLease.DURATION_MS)
    }
}
