package me.hchome.kactor

/**
 * Supervisor strategy controls the *scope* of how a supervisor responds to child failures.
 * The *decision* (what action to take) is determined dynamically by the supervisor actor's
 * [ActorHandler.onSupervise] method.
 *
 *  - [OneForOne] applies the decision only to the failed child.
 *  - [AllForOne] applies the decision to every child of the same parent.
 *
 * Possible decisions returned by [ActorHandler.onSupervise]:
 *  - [Decision.Resume]   – ignore the failure, continue processing
 *  - [Decision.Restart]  – restart the child, preserving unprocessed mailbox messages
 *  - [Decision.Recreate] – restart the child with a fresh mailbox (messages discarded)
 *  - [Decision.Stop]     – stop the child permanently
 *  - [Decision.Escalate] – pass the failure up to the parent's supervisor
 */
sealed interface SupervisorStrategy {

    /** Apply the decision to the failed child only. */
    data object OneForOne : SupervisorStrategy

    /** Apply the decision to all children of the same parent. */
    data object AllForOne : SupervisorStrategy

    enum class Decision {
        Resume,
        Restart,   // restart, keep mailbox
        Recreate,  // restart, discard mailbox
        Stop,
        Escalate,
    }

    companion object {
        val default: SupervisorStrategy = OneForOne
    }
}