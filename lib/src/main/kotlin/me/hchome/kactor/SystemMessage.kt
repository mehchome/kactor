package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred

/**
 * Actor system messages
 */
sealed interface SystemMessage {

    /**
     * Routed by the ActorSystem to the parent actor when a child fails.
     * The parent's handler decides the supervision decision via [ActorHandler.supervise].
     */
    data class SupervisionRequest(
        val childRef: ActorRef,
        val failure: ActorFailure,
        val callback: CompletableDeferred<SupervisorStrategy.Decision>,
    ) : SystemMessage

    /**
     * messages to create an actor
     */
    data class CreateActor(
        val id: String,
        val parent: ActorRef,
        val domain: String,
        val callback: CompletableDeferred<ActorRef>
    ) : SystemMessage

    /**
     * messages to restart an actor
     */
    data class RestartActor(val ref: ActorRef, val recreate: Boolean = true) : SystemMessage

    /**
     * messages to stop an actor
     */
    data class StopActor(val ref: ActorRef) : SystemMessage


    /**
     * message for notifying supervisor when it crashes
     */
    data class ActorFailed(
        val ref: ActorRef,
        val name: String,
        val reason: ActorFailedReason,
        val errorCode: Int,
        val errorMessage: String
    ) : SystemMessage
}