package me.hchome.kactor

import kotlin.time.Duration

/**
 * Actors can be restarted by the supervisor strategy when they crash.
 * [OneForOne] restarts the child actor by default.
 * [AllForOne] restarts all children when one of them crashes.
 * [Resume] Not restarts the child actor but send to exception handling.
 * [Stop] stops the child actor.
 * [Escalate] escalates the failure to the supervisor
 *
 */
sealed interface SupervisorStrategy {

    suspend fun onFailure(failure: ActorFailure) {
        val system = failure.system
        system.notifySystem(
            failure.sender,
            failure.ref,
            "Actor failure",
            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL,
            failure.cause
        )
    }

    suspend fun decide(failure: ActorFailure)

    object OneForOne : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {

        }
    }

    object AllForOne : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {
            val system = failure.system
            val parentRef = failure.ref.parentOf()
            if (parentRef.isNotEmpty()) {
                val allChildReferences = system.childReferences(parentRef)
                for (childRef in allChildReferences) {
                    // send restart messages to all children
                }
            } else { // root actor fall back to OneForOne
                OneForOne.decide(failure)
            }

        }
    }

    object Resume : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {
            // Report sent, no need to do anything
        }
    }

    object Stop : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {

        }
    }

    object Escalate : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {
            failure.supervisor.supervise(failure.ref, failure.sender, failure.message, failure.cause)
        }
    }
}
