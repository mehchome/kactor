package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FsmHandlerTest {

    // ── States ────────────────────────────────────────────────────────────────

    sealed interface Phase : RuntimeState {
        data object Red    : Phase
        data object Green  : Phase
        data object Yellow : Phase
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    data object Tick    : StateMessage
    data object Unknown : StateMessage  // intentionally absent from stateMap

    // ── Snapshot returned by TrafficLightActor ────────────────────────────────

    data class Snapshot(
        val state: RuntimeState,
        val enters: List<RuntimeState>,
        val exits: List<RuntimeState>,
        val transitions: List<Triple<RuntimeState, StateMessage, RuntimeState>>,
        val unhandled: List<Pair<RuntimeState, StateMessage>>
    )

    // ── Compound query messages sent to TrafficLightActor ─────────────────────

    /** Query snapshot without triggering any transition. */
    data class GetSnapshot(val callback: CompletableDeferred<Snapshot>)

    /**
     * Triggers a [step] transition via [ActorContext.sendSelf], then completes [callback]
     * with the updated snapshot inside [FsmHandler.onTransition] or [FsmHandler.onUnhandledTransition].
     *
     * Guarantees ordering: [step] is processed AFTER this message finishes because it is
     * enqueued via [ActorContext.sendSelf] during this message's processing. The actor's
     * mailbox loop is single-message-at-a-time, so [step] can only be dispatched once this
     * message's handler returns.
     */
    data class StepAndSnapshot(val step: StateMessage, val callback: CompletableDeferred<Snapshot>)

    // ── TrafficLightActor ─────────────────────────────────────────────────────

    class TrafficLightActor : AbstractStateMachineActor() {

        private val enters     = mutableListOf<RuntimeState>()
        private val exits      = mutableListOf<RuntimeState>()
        private val transitions = mutableListOf<Triple<RuntimeState, StateMessage, RuntimeState>>()
        private val unhandled  = mutableListOf<Pair<RuntimeState, StateMessage>>()

        // Set by StepAndSnapshot before sendSelf; cleared after use.
        private var pendingCallback: CompletableDeferred<Snapshot>? = null

        override val initialState: RuntimeState = Phase.Red

        override val stateMap = transitionMap {
            on<Tick>(from = Phase.Red,    to = Phase.Green)
            on<Tick>(from = Phase.Green,  to = Phase.Yellow)
            on<Tick>(from = Phase.Yellow, to = Phase.Red)
        }

        private fun snapshot() = Snapshot(
            state       = currentState,
            enters      = enters.toList(),
            exits       = exits.toList(),
            transitions = transitions.toList(),
            unhandled   = unhandled.toList()
        )

        private fun completePending() {
            pendingCallback?.complete(snapshot())
            pendingCallback = null
        }

        context(context: ActorContext)
        override suspend fun onEnter(state: RuntimeState) { enters.add(state) }

        context(context: ActorContext)
        override suspend fun onExit(state: RuntimeState) { exits.add(state) }

        context(context: ActorContext)
        override suspend fun onTransition(from: RuntimeState, via: StateMessage, to: RuntimeState) {
            transitions.add(Triple(from, via, to))
            completePending()
        }

        context(context: ActorContext)
        override suspend fun onUnhandledTransition(state: RuntimeState, message: StateMessage, sender: ActorRef) {
            unhandled.add(Pair(state, message))
            completePending()
        }

        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onOtherMessage(message: Any, sender: ActorRef) {
            when (message) {
                is GetSnapshot    -> message.callback.complete(snapshot())
                is StepAndSnapshot -> {
                    // Install the callback before sendSelf so it's ready when onTransition fires.
                    pendingCallback = message.callback
                    context.sendSelf(message.step)
                }
            }
        }
    }

    // ── BecomeActor ───────────────────────────────────────────────────────────

    /** Tells the actor to push a behavior; [confirm] completes when behavior is active. */
    data class BecomeRequest(val confirm: CompletableDeferred<Unit>)

    /** Tells the outer behavior to push a nested behavior; [confirm] completes when active. */
    data class NestedBecomeRequest(val confirm: CompletableDeferred<Unit>)

    /** Tells the current behavior to unbecome; [confirm] completes after unbecome. */
    data class PopRequest(val confirm: CompletableDeferred<Unit>)

    class BecomeActor : ActorHandler {

        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            when (message) {
                is BecomeRequest -> {
                    context.become { ctx, msg, _ ->
                        when (msg) {
                            is NestedBecomeRequest -> {
                                ctx.become { c2, m, _ ->
                                    when (m) {
                                        is PopRequest           -> { c2.unbecome(); m.confirm.complete(Unit) }
                                        is CompletableDeferred<*> ->
                                            (m as CompletableDeferred<String>).complete("inner")
                                    }
                                }
                                msg.confirm.complete(Unit)
                            }
                            is PopRequest             -> { ctx.unbecome(); msg.confirm.complete(Unit) }
                            is CompletableDeferred<*> ->
                                (msg as CompletableDeferred<String>).complete("outer")
                        }
                    }
                    message.confirm.complete(Unit)
                }
                is CompletableDeferred<*> ->
                    (message as CompletableDeferred<String>).complete("default")
            }
        }
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.createOrGet()
        system.register<TrafficLightActor>(TrafficLightActor::class.simpleName!!)
        system.register<BecomeActor>(BecomeActor::class.simpleName!!)
        system.start()
    }

    @AfterEach
    fun teardown() { system.shutdownGracefully() }

    /**
     * Queries the actor's snapshot without triggering a transition.
     * Safe to call any time; the result reflects the state when this message is processed.
     */
    private suspend fun snapshot(ref: ActorRef): Snapshot {
        val d = CompletableDeferred<Snapshot>()
        system.send(ref, GetSnapshot(d))
        return withTimeout(2.seconds) { d.await() }
    }

    /**
     * Triggers [step] via the actor's mailbox, waits until [FsmHandler.onTransition]
     * (or [FsmHandler.onUnhandledTransition]) completes, and returns the resulting snapshot.
     *
     * Ordering guarantee: the snapshot is taken inside the same actor message-processing
     * coroutine that handled [step], so all hook side-effects are visible.
     */
    private suspend fun step(ref: ActorRef, step: StateMessage): Snapshot {
        val d = CompletableDeferred<Snapshot>()
        system.send(ref, StepAndSnapshot(step, d))
        return withTimeout(2.seconds) { d.await() }
    }

    private suspend fun awaitBehaviorLabel(ref: ActorRef): String {
        val d = CompletableDeferred<String>()
        system.send(ref, d)
        return withTimeout(2.seconds) { d.await() }
    }

    private suspend fun awaitConfirm(block: (CompletableDeferred<Unit>) -> Unit) {
        val d = CompletableDeferred<Unit>()
        block(d)
        withTimeout(2.seconds) { d.await() }
    }

    // ── FSM state tests ───────────────────────────────────────────────────────

    @Test
    fun `initial state is Red`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        assertEquals(Phase.Red, snapshot(actor).state)
    }

    @Test
    fun `Tick transitions Red to Green`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        assertEquals(Phase.Green, step(actor, Tick).state)
    }

    @Test
    fun `full Red Green Yellow Red cycle`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        assertEquals(Phase.Green,  step(actor, Tick).state)
        assertEquals(Phase.Yellow, step(actor, Tick).state)
        assertEquals(Phase.Red,    step(actor, Tick).state)
    }

    // ── Lifecycle hook ordering ───────────────────────────────────────────────

    @Test
    fun `onExit fires with old state, onEnter fires with new state`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        val snap = step(actor, Tick)  // Red → Green

        assertEquals(listOf(Phase.Red),   snap.exits)
        assertEquals(listOf(Phase.Green), snap.enters)
    }

    @Test
    fun `onTransition receives correct from, via, to`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        val snap = step(actor, Tick)

        val (from, via, to) = snap.transitions.single()
        assertEquals(Phase.Red,   from)
        assertEquals(Tick,        via)
        assertEquals(Phase.Green, to)
    }

    @Test
    fun `onTransition is called exactly once per Tick`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        step(actor, Tick)
        step(actor, Tick)
        val snap = step(actor, Tick)

        assertEquals(3, snap.transitions.size)
    }

    @Test
    fun `hook ordering accumulates correctly across multiple ticks`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        step(actor, Tick)              // Red → Green
        val snap = step(actor, Tick)   // Green → Yellow

        assertEquals(listOf(Phase.Red,   Phase.Green),  snap.exits)
        assertEquals(listOf(Phase.Green, Phase.Yellow), snap.enters)
        assertEquals(2, snap.transitions.size)
    }

    // ── Unhandled transition ──────────────────────────────────────────────────

    @Test
    fun `onUnhandledTransition fires for unmapped state-message pair`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        step(actor, Tick)                         // Red → Green (so current state is Green)
        val snap = step(actor, Unknown)           // (Green, Unknown) is not in stateMap

        assertTrue(snap.unhandled.isNotEmpty())
        val (state, msg) = snap.unhandled.single()
        assertEquals(Phase.Green, state)
        assertEquals(Unknown, msg)
    }

    @Test
    fun `unhandled transition does not change state`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        val snap = step(actor, Unknown)

        assertEquals(Phase.Red, snap.state)
    }

    @Test
    fun `no onTransition entry for unhandled transition`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        val snap = step(actor, Unknown)

        assertTrue(snap.transitions.isEmpty())
    }

    @Test
    fun `onOtherMessage handles non-StateMessage without changing state`() = runBlocking {
        val actor = system.actorOf<TrafficLightActor>()
        val snap = snapshot(actor)          // GetSnapshot is not a StateMessage

        assertEquals(Phase.Red, snap.state)
        assertTrue(snap.transitions.isEmpty())
    }

    // ── become / unbecome ─────────────────────────────────────────────────────

    @Test
    fun `default handler is used before become is called`() = runBlocking {
        val actor = system.actorOf<BecomeActor>()
        assertEquals("default", awaitBehaviorLabel(actor))
    }

    @Test
    fun `become replaces onMessage for subsequent messages`() = runBlocking {
        val actor = system.actorOf<BecomeActor>()

        awaitConfirm { system.send(actor, BecomeRequest(it)) }

        assertEquals("outer", awaitBehaviorLabel(actor))
    }

    @Test
    fun `unbecome restores default onMessage`() = runBlocking {
        val actor = system.actorOf<BecomeActor>()

        awaitConfirm { system.send(actor, BecomeRequest(it)) }
        awaitConfirm { system.send(actor, PopRequest(it)) }

        assertEquals("default", awaitBehaviorLabel(actor))
    }

    @Test
    fun `nested become — innermost behavior handles messages`() = runBlocking {
        val actor = system.actorOf<BecomeActor>()

        awaitConfirm { system.send(actor, BecomeRequest(it)) }       // push outer
        awaitConfirm { system.send(actor, NestedBecomeRequest(it)) } // push inner

        assertEquals("inner", awaitBehaviorLabel(actor))
    }

    @Test
    fun `unbecome from nested restores outer behavior`() = runBlocking {
        val actor = system.actorOf<BecomeActor>()

        awaitConfirm { system.send(actor, BecomeRequest(it)) }
        awaitConfirm { system.send(actor, NestedBecomeRequest(it)) }
        awaitConfirm { system.send(actor, PopRequest(it)) }          // pop inner

        assertEquals("outer", awaitBehaviorLabel(actor))
    }

    @Test
    fun `unbecome from outer restores default handler`() = runBlocking {
        val actor = system.actorOf<BecomeActor>()

        awaitConfirm { system.send(actor, BecomeRequest(it)) }
        awaitConfirm { system.send(actor, PopRequest(it)) }          // pop outer

        assertEquals("default", awaitBehaviorLabel(actor))
    }
}