package com.yuzakikokuban.ahucm.integration

class ManagedCancellationJUnitTest {
    @org.junit.Test fun explicitResumeRecoversCanceledBoundTableAfterLeaseEnds() {
        org.junit.Assert.assertTrue(ManagedCancellation.canResume("pending", false, "verifying", "33162269"))
        org.junit.Assert.assertTrue(ManagedCancellation.canResume("pending", false, "courses-writing", "33162269"))
        org.junit.Assert.assertFalse(ManagedCancellation.canResume("pending", true, "verifying", "33162269"))
    }

    @org.junit.Test fun ambiguousCreationStillCannotBeResumed() {
        org.junit.Assert.assertFalse(ManagedCancellation.canResume("pending", false, "table-creating", ""))
        org.junit.Assert.assertTrue(ManagedCancellation.canResume("pending", false, "accepted", ""))
    }

    @org.junit.Test fun cancellationRecoveryPreservesWriteBoundaries() {
        ManagedCancellationTest.main(emptyArray())
    }
}
