package me.hchome.kactor

import kotlin.time.Duration

/**
 * Actors can be restarted by the supervisor strategy when they crash.
 * [OneForOne] restarts the child actor by default.
 * [AllForOne] restarts all children when one of them crashes.
 * [Backoff] restarts the child actor with an exponential backoff delay.
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
            val parentRef = failure.ref.parentOf()
            if(parentRef.isNullOrEmpty())

        }
    }
    data class Backoff(val initialDelay: Duration, val maxDelay: Duration) : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {

        }
    }
    object Resume : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {

        }
    }
    object Stop : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {

        }
    }
    object Escalate : SupervisorStrategy {
        override suspend fun decide(failure: ActorFailure) {

        }
    }
}
