package me.hchome.kactor

/**
 * Defines the scope of how a supervision decision is applied when a child actor fails.
 * The actual [Decision] is determined by [ActorHandler.supervise] based on the failure cause.
 *
 * [OneForOne] applies the decision only to the failing child (default).
 * [AllForOne] applies the decision to all siblings of the failing child.
 * [Stop] always stops the failing child, regardless of the handler's decision.
 * [Resume] always resumes the failing child, regardless of the handler's decision.
 */
sealed interface SupervisorStrategy {

    /**
     * Notifies the system about the failure, applies the strategy, and returns
     * the actual decision that was carried out.
     */
    suspend fun handle(failure: ActorFailure, decision: Decision): Decision {
        failure.system.notifySystem(
            failure.sender,
            failure.ref,
            "Actor failure: ${failure.cause}",
            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL,
        )
        return apply(failure, decision)
    }

    /**
     * Applies the decision to the appropriate set of actors and returns the
     * effective decision that was carried out.
     */
    suspend fun apply(failure: ActorFailure, decision: Decision): Decision

    /**
     * Applies the handler's decision only to the failing child actor.
     */
    object OneForOne : SupervisorStrategy {
        override suspend fun apply(failure: ActorFailure, decision: Decision): Decision {
            failure.system.processFailure(failure.ref, decision)
            return decision
        }
    }

    /**
     * Applies the handler's decision to all sibling children of the failing actor.
     * Falls back to [OneForOne] for root actors.
     */
    object AllForOne : SupervisorStrategy {
        override suspend fun apply(failure: ActorFailure, decision: Decision): Decision {
            val system = failure.system
            val parentRef = failure.ref.parentOf()
            return if (parentRef.isNotEmpty()) {
                for (childRef in system.childReferences(parentRef)) {
                    system.processFailure(childRef, decision)
                }
                decision
            } else {
                OneForOne.apply(failure, decision)
            }
        }
    }

    /**
     * Always stops the failing child, ignoring the handler's decision.
     */
    object Stop : SupervisorStrategy {
        override suspend fun apply(failure: ActorFailure, decision: Decision): Decision {
            failure.system.processFailure(failure.ref, Decision.Stop)
            return Decision.Stop
        }
    }

    /**
     * Always resumes the failing child, ignoring the handler's decision.
     */
    object Resume : SupervisorStrategy {
        override suspend fun apply(failure: ActorFailure, decision: Decision): Decision {
            return Decision.Resume
        }
    }

    enum class Decision {
        Recreate, Restart, Stop, Resume
    }
}