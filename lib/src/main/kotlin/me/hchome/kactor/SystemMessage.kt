package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred

/**
 * Actor system messages
 */
sealed interface SystemMessage {

    /**
     * messages to create an actor
     */
    data class CreateActor(
        val id: String,
        val parent: ActorRef,
        val singleton: Boolean,
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
}