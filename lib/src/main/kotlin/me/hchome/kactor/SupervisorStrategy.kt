package me.hchome.kactor

/**
 * Actors can be restarted by the supervisor strategy when they crash.
 * [OneForOne] restarts the child actor by default.
 * [AllForOne] restarts all children when one of them crashes.
 * [Escalate] escalates the failure to the supervisor
 *
 */
sealed interface SupervisorStrategy {

    suspend fun onFailure(failure: ActorFailure): Decision {
        val system = failure.system
        system.notifySystem(
            failure.sender,
            failure.ref,
            "Actor failure",
            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL,
            failure.cause
        )
        return decide(failure)
    }

    suspend fun decide(failure: ActorFailure): Decision

    object OneForOne : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure): Decision {
            val system = failure.system
            system.processFailure(failure.ref, Decision.Recreate)
            return Decision.Recreate
        }
    }

    object OneForOneRetained : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure): Decision {
            val system = failure.system
            system.processFailure(failure.ref, Decision.Restart)
            return Decision.Restart
        }
    }

    object AllForOne : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure): Decision {
            val system = failure.system
            val parentRef = failure.ref.parentOf()
            return if (parentRef.isNotEmpty()) {
                val allChildReferences = system.childReferences(parentRef)
                for (childRef in allChildReferences) {
                    // send restart messages to all children
                    system.processFailure(childRef, Decision.Restart)
                }
                Decision.Restart
            } else { // the root actor falls back to OneForOne
                OneForOne.decide(failure)
            }
        }
    }

//    object Resume : SupervisorStrategy {
//        override suspend fun decide(failure: ActorFailure): Decision {
//            // Report sent, no need to do anything
//            return Decision.Resume
//        }
//    }
//
//    object Stop : SupervisorStrategy {
//        override suspend fun decide(failure: ActorFailure): Decision {
//            val system = failure.system
//            system.processFailure(failure.ref, Decision.Stop)
//            return Decision.Stop
//        }
//    }

    object Escalate : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure): Decision {
            return failure.supervisor.supervise(failure.ref, failure.sender, failure.message, failure.cause)
        }
    }

    enum class Decision {
        Recreate, Restart, Stop, Resume
    }
}