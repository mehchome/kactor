package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import me.hchome.kactor.ActorFailure
import me.hchome.kactor.ActorRef
import me.hchome.kactor.SupervisorStrategy

/**
 * Message wrapper used inside an actor's mailbox.
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

    /** Routed from a failing child to its supervisor actor for supervision handling. */
    data class SuperviseEnvelope(
        val failure: ActorFailure,
        val callback: CompletableDeferred<SupervisorStrategy.Decision>
    ) : ActorEnvelope {
        override val message: Any get() = failure
        override val sender: ActorRef get() = failure.ref
    }
}