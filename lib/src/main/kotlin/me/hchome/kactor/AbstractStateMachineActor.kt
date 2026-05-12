package me.hchome.kactor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Ready-to-use implementation of [FsmHandler].
 *
 * Subclasses provide [initialState] and [stateMap] and override whichever lifecycle hooks
 * they need ([onEnter], [onExit], [onTransition], [onUnhandledTransition], [onOtherMessage]).
 *
 * Message routing (inside [onMessage]):
 * - [StateMessage] → looks up `(currentState, messageClass)` in [stateMap].
 *     - Found    → [onExit] → emit new state → [onEnter] → [onTransition]
 *     - Not found → [onUnhandledTransition]; state is unchanged
 * - Anything else → [onOtherMessage]
 *
 * [stateFlow] exposes the current state as a [StateFlow] for external observers.
 *
 * Example:
 * ```kotlin
 * class TrafficLight : AbstractStateMachineActor() {
 *
 *     sealed interface Phase : RuntimeState {
 *         data object Red : Phase; data object Green : Phase; data object Yellow : Phase
 *     }
 *     data object Tick : StateMessage
 *
 *     override val initialState = Phase.Red
 *     override val stateMap = transitionMap {
 *         on<Tick>(from = Phase.Red,    to = Phase.Green)
 *         on<Tick>(from = Phase.Green,  to = Phase.Yellow)
 *         on<Tick>(from = Phase.Yellow, to = Phase.Red)
 *     }
 *
 *     context(context: ActorContext)
 *     override suspend fun onTransition(from: RuntimeState, via: StateMessage, to: RuntimeState) {
 *         logger.info("{} ──[{}]──▶ {}", from, via::class.simpleName, to)
 *     }
 *
 *     context(context: ActorContext)
 *     override suspend fun onOtherMessage(message: Any, sender: ActorRef) = Unit
 * }
 * ```
 */
abstract class AbstractStateMachineActor : FsmHandler {

    /** The state this actor starts in. Evaluated once when the state flow is first accessed. */
    protected abstract val initialState: RuntimeState

    /**
     * Logger used for transition tracing (DEBUG) and unhandled-transition warnings (WARN).
     * Override to supply a custom logger; defaults to the concrete subclass name.
     */
    protected open val logger: Logger = LoggerFactory.getLogger(this::class.java)

    private val _stateFlow: MutableStateFlow<RuntimeState> by lazy {
        MutableStateFlow(initialState)
    }

    /**
     * Observable stream of state changes.
     * Emits the *new* state immediately after [currentState] is updated, before [onEnter] is called.
     */
    val stateFlow: StateFlow<RuntimeState>
        get() = _stateFlow.asStateFlow()

    override val currentState: RuntimeState
        get() = _stateFlow.value

    context(context: ActorContext)
    final override suspend fun onMessage(message: Any, sender: ActorRef) {
        logger.trace("Actor[{}] received: {}", context.ref, message)
        when (message) {
            is StateMessage -> handleStateMessage(message, sender)
            else -> onOtherMessage(message, sender)
        }
    }

    context(context: ActorContext)
    private suspend fun handleStateMessage(message: StateMessage, sender: ActorRef) {
        val current = _stateFlow.value
        val next = stateMap[Pair(current, message::class)]
        if (next != null) {
            logger.debug(
                "Actor[{}] {} ──[{}]──▶ {}",
                context.ref, current, message::class.simpleName, next
            )
            onExit(current)
            _stateFlow.emit(next)
            onEnter(next)
            onTransition(current, message, next)
        } else {
            logger.warn(
                "Actor[{}] no transition: state={} message={}",
                context.ref, current, message::class.simpleName
            )
            onUnhandledTransition(current, message, sender)
        }
    }
}