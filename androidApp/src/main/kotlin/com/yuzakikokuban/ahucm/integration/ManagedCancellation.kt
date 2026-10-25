package com.yuzakikokuban.ahucm.integration

/** Pure journal-state policy, shared by the provider and the cancellation tests. */
internal object ManagedCancellation {
    data class Decision(val state: String, val phase: String, val message: String)
    fun request(state: String, phase: String, claimed: Boolean): Decision = when {
        state in listOf("applied", "failed") -> Decision(state, phase, "management_stopped")
        state == "accepted" && phase == "accepted" && !claimed -> Decision("failed", "canceled", "canceled_before_host_take")
        else -> Decision("pending", phase, "cancel_requested")
    }
    // Explicit re-enable may repair a bound table after cancellation settles.
    // An unacknowledged creation with no target remains ambiguous.
    fun canResume(state: String, claimed: Boolean, phase: String = "", tableId: String = ""): Boolean =
        !claimed && (state in listOf("applied", "failed") ||
            (state == "pending" && (phase == "accepted" || tableId.isNotEmpty())))
}
