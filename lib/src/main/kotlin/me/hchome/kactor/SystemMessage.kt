package me.hchome.kactor

/**
 * Actor system messages
 */
sealed interface SystemMessage {

    /**
     * messages to create an actor
     */
    data class CreateActor(val ref: ActorRef, val singleton: Boolean, val domain: String) : SystemMessage
}