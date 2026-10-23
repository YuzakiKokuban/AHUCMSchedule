package com.yuzakikokuban.ahucm.integration

/** Runnable without Android or JUnit: exercises the provider's real state policy. */
object ManagedCancellationTest {
    @JvmStatic fun main(args: Array<String>) {
        val queued = ManagedCancellation.request("accepted", "accepted", false)
        check(queued.state == "failed" && queued.phase == "canceled") { "Unclaimed accepted job must become terminal" }
        val claimed = ManagedCancellation.request("accepted", "accepted", true)
        check(claimed.state == "pending" && claimed.phase == "accepted") { "Claimed job must receive cancellation through checkpoint" }
        val writing = ManagedCancellation.request("pending", "courses-writing", true)
        check(writing.state == "pending" && writing.phase == "courses-writing") { "Uncertain write phase must not be erased" }
        val completed = ManagedCancellation.request("applied", "verified", false)
        check(completed.state == "applied" && completed.phase == "verified") { "Cancellation must not undo a verified receipt" }
        check(!ManagedCancellation.canResume("pending", false)) { "Uncertain operation must not resume" }
        check(!ManagedCancellation.canResume("failed", true)) { "Active host lease must not be reset" }
        check(ManagedCancellation.canResume("failed", false)) { "Terminal canceled job can resume explicitly" }
        check(ManagedCancellation.canResume("applied", false))
        println("ManagedCancellation policy: 8 checks passed")
    }
}
