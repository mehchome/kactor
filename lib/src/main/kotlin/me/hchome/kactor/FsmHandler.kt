package me.hchome.kactor

/**
 * Extends [ActorHandler] with a finite-state-machine (FSM) contract.
 *
 * Implementors define the valid transitions in [stateMap] and override the lifecycle hooks
 * to react to state changes. The concrete logic (state storage, message routing, transition
 * application) lives in the abstract class that implements this interface.
 *
 * Typical usage:
 * ```kotlin
 * class TrafficLightActor : AbstractStateMachineActor(), FsmHandler {
 *
 *     override val initialState = Red
 *
 *     override val stateMap = transitionMap {
 *         on<Tick>(from = Red,    to = Green)
 *         on<Tick>(from = Green,  to = Yellow)
 *         on<Tick>(from = Yellow, to = Red)
 *     }
 *
 *     context(context: ActorContext)
 *     override suspend fun onTransition(from: RuntimeState, via: StateMessage, to: RuntimeState) {
 *         logger.info("{} → {}", from, to)
 *     }
 *
 *     context(context: ActorContext)
 *     override suspend fun onOtherMessage(message: Any, sender: ActorRef) = Unit
 * }
 * ```
 *
 * @see RuntimeState
 * @see StateMessage
 * @see StateTransitionMap
 * @see transitionMap
 */
interface FsmHandler : ActorHandler {

    /** The current FSM state. Updated atomically before [onEnter] is called. */
    val currentState: RuntimeState

    /**
     * Transition table: *(currentState, messageClass)* → *nextState*.
     * Build with [transitionMap].
     */
    val stateMap: StateTransitionMap

    /**
     * Called when the FSM **enters** [state], after [currentState] has been updated
     * and after [onExit] of the previous state has returned.
     */
    context(context: ActorContext)
    suspend fun onEnter(state: RuntimeState) {}

    /**
     * Called when the FSM **exits** [state], before [currentState] is updated.
     */
    context(context: ActorContext)
    suspend fun onExit(state: RuntimeState) {}

    /**
     * Called after each successful state transition.
     *
     * @param from the state before the transition
     * @param via  the [StateMessage] that triggered the transition
     * @param to   the state after the transition (same as [currentState])
     */
    context(context: ActorContext)
    suspend fun onTransition(from: RuntimeState, via: StateMessage, to: RuntimeState) {}

    /**
     * Called when a [StateMessage] arrives but the transition table has no entry for
     * *(currentState, messageClass)*. The default implementation does nothing.
     */
    context(context: ActorContext)
    suspend fun onUnhandledTransition(state: RuntimeState, message: StateMessage, sender: ActorRef) {}

    /**
     * Called for messages that do not implement [StateMessage].
     * Override to handle domain-specific non-transition messages.
     */
    context(context: ActorContext)
    suspend fun onOtherMessage(message: Any, sender: ActorRef) {}
}
