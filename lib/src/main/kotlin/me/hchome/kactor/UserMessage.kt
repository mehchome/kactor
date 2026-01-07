package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred

/**
 * User message send
 */
sealed interface UserMessage {
    data class Tell(
        val target: ActorRef,
        val sender: ActorRef,
        val message: Any
    ): UserMessage

    data class Ask(
        val target: ActorRef,
        val sender: ActorRef,
        val message: Any,
        val callback: CompletableDeferred<out Any>
    ): UserMessage
}