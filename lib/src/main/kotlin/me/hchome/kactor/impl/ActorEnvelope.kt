package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import me.hchome.kactor.ActorRef

/**
 * Message Wrapper that uses for
 */
sealed interface ActorEnvelope {
    val message: Any
    val sender: ActorRef

    data class SendActorEnvelope(
        override val message: Any,
        override val sender: ActorRef
    ) : ActorEnvelope

    data class AskActorEnvelope<T>(
        override val message: Any,
        override val sender: ActorRef,
        val callback: CompletableDeferred<in T>
    ) : ActorEnvelope
}