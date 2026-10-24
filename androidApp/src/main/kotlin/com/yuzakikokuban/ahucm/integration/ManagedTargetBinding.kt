package com.yuzakikokuban.ahucm.integration

/** Validate target changes against the last durable mutation checkpoint. */
internal object ManagedTargetBinding {
    fun accepts(previousId: String, previousPhase: String, receivedId: String, receivedPhase: String, pendingCheckpoint: Boolean): Boolean =
        previousId.isEmpty() || receivedId == previousId ||
            (pendingCheckpoint && previousPhase == "table-creating" &&
                receivedPhase == "table-created" && receivedId.isNotEmpty())
}
