package com.yuzakikokuban.ahucm.integration

/** Pure journal-state policy, shared by the provider and the cancellation tests. */
internal object ManagedCancellation {
    data class Decision(val state: String, val phase: String, val message: String)
    fun request(state: String, phase: String, claimed: Boolean): Decision = when {
        state in listOf("applied", "failed") -> Decision(state, phase, "management_stopped")
        state == "accepted" && phase == "accepted" && !claimed -> Decision("failed", "canceled", "canceled_before_host_take")
        else -> Decision("pending", phase, "cancel_requested")
    }
    fun canResume(state: String, claimed: Boolean): Boolean = !claimed && state in listOf("applied", "failed")
}
