package com.yuzakikokuban.ahucm.integration

import org.junit.Assert.*
import org.junit.Test

class ManagedTargetBindingTest {
    @Test fun recreatedTableCanReplaceDeletedBindingOnlyAtTheAuthorizedCreationCheckpoint() {
        assertTrue(ManagedTargetBinding.accepts("10", "table-creating", "20", "table-created", true))
        assertFalse(ManagedTargetBinding.accepts("10", "accepted", "20", "table-created", true))
        assertFalse(ManagedTargetBinding.accepts("10", "table-creating", "20", "courses-writing", true))
        assertFalse(ManagedTargetBinding.accepts("10", "table-creating", "20", "table-created", false))
        assertFalse(ManagedTargetBinding.accepts("10", "table-creating", "", "table-created", true))
    }

    @Test fun existingBindingAndInitialCreationKeepTheirOriginalBehavior() {
        assertTrue(ManagedTargetBinding.accepts("10", "verifying", "10", "verified", false))
        assertTrue(ManagedTargetBinding.accepts("", "table-creating", "20", "table-created", true))
        assertFalse(ManagedTargetBinding.accepts("10", "verifying", "20", "verified", false))
    }
}
