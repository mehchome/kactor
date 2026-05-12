package me.hchome.kactor

import kotlin.reflect.KClass

/**
 * Marker interface for FSM state values.
 * Implement with a sealed interface or enum to enumerate all valid states.
 *
 * Example:
 * ```
 * sealed interface ConnectionState : RuntimeState {
 *     data object Disconnected : ConnectionState
 *     data object Connecting   : ConnectionState
 *     data object Connected    : ConnectionState
 * }
 * ```
 */
interface RuntimeState

/**
 * Marker interface for messages that can trigger a state transition in an [FsmHandler].
 * Messages that do not implement this interface are routed to [FsmHandler.onOtherMessage].
 */
interface StateMessage

/**
 * Transition table used by [FsmHandler]: maps *(currentState, messageClass)* to *nextState*.
 * Construct one with the [transitionMap] DSL.
 */
typealias StateTransitionMap = Map<Pair<RuntimeState, KClass<out StateMessage>>, RuntimeState>

/**
 * DSL builder for [StateTransitionMap].
 * Use via [transitionMap].
 */
class TransitionBuilder @PublishedApi internal constructor() {

    @PublishedApi
    internal val map = mutableMapOf<Pair<RuntimeState, KClass<out StateMessage>>, RuntimeState>()

    /**
     * Declares that while in state [from], receiving a message of type [M] should
     * transition to state [to].
     *
     * Example:
     * ```
     * on<ConnectMsg>(from = Disconnected, to = Connecting)
     * ```
     */
    inline fun <reified M : StateMessage> on(from: RuntimeState, to: RuntimeState) {
        map[Pair(from, M::class)] = to
    }

    @PublishedApi
    internal fun build(): StateTransitionMap = map.toMap()
}

/**
 * Builds a [StateTransitionMap] using the [TransitionBuilder] DSL.
 *
 * Example:
 * ```kotlin
 * override val stateMap = transitionMap {
 *     on<ConnectMsg>    (from = Disconnected, to = Connecting)
 *     on<ConnectedMsg>  (from = Connecting,   to = Connected)
 *     on<DisconnectMsg> (from = Connected,     to = Disconnected)
 * }
 * ```
 */
fun transitionMap(block: TransitionBuilder.() -> Unit): StateTransitionMap =
    TransitionBuilder().apply(block).build()