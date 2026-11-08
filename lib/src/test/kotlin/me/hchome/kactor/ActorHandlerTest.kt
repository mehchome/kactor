package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ActorHandlerTest {

    // ── Handlers ─────────────────────────────────────────────────────────────

    /** Answers asks by return value; "boom" throws. Completes CompletableDeferred messages. */
    class ReplyingActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef): Any =
            if (message == "boom") throw IllegalStateException("boom") else "re: $message"

        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is CompletableDeferred<*>) (message as CompletableDeferred<Unit>).complete(Unit)
        }
    }

    /** Asks the actor to reply with [reply] instead of a plain value. */
    data class DeferredReply(val reply: Deferred<Any?>)

    /** Replies with a [DeferredReply]'s deferred; "never" replies with a task that runs until the actor stops. */
    class DeferredActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef): Any = when (message) {
            is DeferredReply -> message.reply
            "never" -> context.async { awaitCancellation() }
            else -> "re: $message"
        }

        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is CompletableDeferred<*>) (message as CompletableDeferred<Unit>).complete(Unit)
        }
    }

    /** Asks [to] with [message] and waits for its reply, at most [timeout]. */
    data class Forward(val to: ActorRef, val message: Any, val timeout: Duration)

    class ForwardingActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef): Any {
            require(message is Forward)
            return context.askAndWait<Any>(message.message, message.to, message.timeout)
        }

        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is CompletableDeferred<*>) (message as CompletableDeferred<Unit>).complete(Unit)
        }
    }

    /** Does not override onAsk. */
    class NoAskActor : ActorHandler {
        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is CompletableDeferred<*>) (message as CompletableDeferred<Unit>).complete(Unit)
        }
    }

    /** Signals [started] from inside onMessage, then suspends until [gate]. */
    data class Block(val started: CompletableDeferred<Unit>, val gate: CompletableDeferred<Unit>)

    class BlockingActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is Block) {
                message.started.complete(Unit)
                message.gate.await()
            }
        }
    }

    class FailingStartActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun preStart() = throw IllegalStateException("cannot start")
    }

    /** Default onSupervise; spawns a FailingStartActor child on demand. */
    class StartParent : ActorHandler {
        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is CompletableDeferred<*>) (message as CompletableDeferred<Unit>).complete(Unit)
        }
    }

    class PropsActor(val id: Int, val label: String = "none") : ActorHandler {
        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef): Any = "$id/$label"
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private lateinit var system: ActorSystem
    private val notifications = ConcurrentLinkedQueue<ActorSystemNotificationMessage>()

    @BeforeEach
    fun setup() {
        system = ActorSystem.createOrGet()
        system.register<ReplyingActor>("ReplyingActor")
        system.register<NoAskActor>("NoAskActor")
        system.register<DeferredActor>("DeferredActor")
        system.register<ForwardingActor>("ForwardingActor")
        system.register<BlockingActor>("BlockingActor")
        system.register<FailingStartActor>("FailingStartActor")
        system.register<StartParent>("StartParent")
        system.register<PropsActor>("PropsActor")
        system += ActorSystemMessageListener { notifications += it }
        system.start()
    }

    @AfterEach
    fun teardown() { system.shutdownGracefully() }

    private fun notified(type: ActorSystemNotificationMessage.NotificationType, ref: ActorRef) =
        notifications.filter { it.type == type && it.receiver == ref }

    private suspend fun awaitNotification(type: ActorSystemNotificationMessage.NotificationType, ref: ActorRef) =
        withTimeout(3.seconds) {
            while (notified(type, ref).isEmpty()) delay(10)
            notified(type, ref).first()
        }

    private suspend fun assertResponsive(ref: ActorRef) {
        val check = CompletableDeferred<Unit>()
        system.send(ref, check)
        withTimeout(2.seconds) { check.await() }
    }

    // ── Ask ──────────────────────────────────────────────────────────────────

    @Nested
    inner class Ask {
        @Test
        fun `return-value onAsk replies`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<ReplyingActor>()
            assertEquals("re: hi", withTimeout(2.seconds) { system.ask<Any>(ref, ActorRef.EMPTY, "hi").await() })
        }

        @Test
        fun `ask fails with the handler exception even when the decision is Restart`() = runBlocking<Unit> {
            // top-level actor: the system supervisor decides Restart
            val ref = system.actorOfSuspend<ReplyingActor>()

            val error = assertThrows<IllegalStateException> {
                withTimeout(2.seconds) { system.ask<Any>(ref, ActorRef.EMPTY, "boom").await() }
            }
            assertEquals("boom", error.message)

            awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, ref)
            assertResponsive(ref)
        }

        @Test
        fun `ask to a handler without onAsk fails fast and does not restart it`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<NoAskActor>()

            val error = assertThrows<AskNotHandledException> {
                withTimeout(2.seconds) { system.ask<Any>(ref, ActorRef.EMPTY, "hi").await() }
            }
            assertEquals(ref, error.ref)

            assertResponsive(ref)
            assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, ref).isEmpty())
            assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, ref).isEmpty())
        }
    }

    // ── Deferred ask reply ───────────────────────────────────────────────────

    @Nested
    inner class DeferredAsk {
        private fun ask(ref: ActorRef, message: Any) = system.ask<Any>(ref, ActorRef.EMPTY, message)

        @Test
        fun `deferred reply answers once completed without holding the mailbox`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<DeferredActor>()
            val gate = CompletableDeferred<Any?>()
            val pending = ask(ref, DeferredReply(gate))

            // the mailbox moved on: a later ask is answered while the first is still pending
            assertEquals("re: ping", withTimeout(2.seconds) { ask(ref, "ping").await() })
            assertFalse(pending.isCompleted)

            gate.complete("late")
            assertEquals("late", withTimeout(2.seconds) { pending.await() })
        }

        @Test
        fun `failed deferred reply fails the ask and is not supervised`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<DeferredActor>()
            val gate = CompletableDeferred<Any?>()
            val pending = ask(ref, DeferredReply(gate))

            gate.completeExceptionally(IllegalStateException("late boom"))
            val error = assertThrows<IllegalStateException> { withTimeout(2.seconds) { pending.await() } }
            assertEquals("late boom", error.message)

            assertResponsive(ref)
            delay(300.milliseconds) // a supervised failure would be reported by now
            assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, ref).isEmpty())
            assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, ref).isEmpty())
        }

        @Test
        fun `null deferred reply fails the ask`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<DeferredActor>()
            val pending = ask(ref, DeferredReply(CompletableDeferred<Any?>(null)))

            assertThrows<IllegalStateException> { withTimeout(2.seconds) { pending.await() } }
            assertResponsive(ref)
        }

        @Test
        fun `deferred reply cancelled by stopping the actor does not leave the ask hanging`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<DeferredActor>()
            val pending = ask(ref, "never")
            // asks are processed in order: once this one is answered, "never" has been handled
            withTimeout(2.seconds) { ask(ref, "ping").await() }

            system.destroyActor(ref)
            assertNotNull(withTimeoutOrNull(2.seconds) { pending.join() }, "ask was left hanging")
            assertIs<CancellationException>(runCatching { pending.await() }.exceptionOrNull())
        }
    }

    // ── askAndWait ───────────────────────────────────────────────────────────

    @Nested
    inner class AskAndWait {
        @Test
        fun `askAndWait returns the reply`() = runBlocking<Unit> {
            val target = system.actorOfSuspend<ReplyingActor>()
            val ref = system.actorOfSuspend<ForwardingActor>()
            val reply = system.ask<Any>(ref, ActorRef.EMPTY, Forward(target, "hi", 2.seconds))
            assertEquals("re: hi", withTimeout(2.seconds) { reply.await() })
        }

        @Test
        fun `askAndWait timeout is a supervised failure, not a cancellation`() = runBlocking<Unit> {
            val target = system.actorOfSuspend<DeferredActor>()
            val ref = system.actorOfSuspend<ForwardingActor>()
            val never = DeferredReply(CompletableDeferred())

            val error = assertThrows<AskTimeoutException> {
                withTimeout(2.seconds) {
                    system.ask<Any>(ref, ActorRef.EMPTY, Forward(target, never, 100.milliseconds)).await()
                }
            }
            assertEquals(target, error.ref)

            // supervised like any handler failure: the system supervisor restarts the actor
            awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, ref)
            assertResponsive(ref)
        }
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    @Test
    fun `stopping an actor mid-message is not reported as a failure`() = runBlocking<Unit> {
        val ref = system.actorOfSuspend<BlockingActor>()
        val block = Block(CompletableDeferred(), CompletableDeferred())
        system.send(ref, block)
        withTimeout(2.seconds) { block.started.await() }

        system.destroyActor(ref)
        awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, ref)
        delay(300.milliseconds) // a cancellation mistaken for a failure would be supervised by now

        assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, ref).isEmpty())
        assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, ref).isEmpty())
    }

    // ── preStart ─────────────────────────────────────────────────────────────

    @Nested
    inner class PreStart {
        @Test
        fun `failed preStart of a top-level actor is supervised and stops it`() = runBlocking<Unit> {
            val ref = system.actorOfSuspend<FailingStartActor>()

            val fatal = awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, ref)
            val cause = assertIs<ActorInitializationException>(fatal.exception)
            assertEquals("cannot start", cause.cause?.message)

            awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, ref)
            assertFalse(ref in system)
            assertTrue(notified(ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, ref).isEmpty())
        }

        @Test
        fun `failed preStart of a child is stopped by the default onSupervise`() = runBlocking<Unit> {
            val parent = system.actorOfSuspend<StartParent>()
            val child = system.actorOfSuspend<FailingStartActor>(parent = parent)

            awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, child)
            assertFalse(child in system)
            assertTrue(parent in system)
            assertResponsive(parent)
        }
    }

    // ── Registry & factory ───────────────────────────────────────────────────

    @Nested
    inner class Registry {
        @Test
        fun `lookups by class find the domain the class was registered under`() {
            assertTrue(ReplyingActor::class in system)
            assertEquals("ReplyingActor", system.get<ReplyingActor>().domain)
            assertEquals("ReplyingActor", system.findName(ReplyingActor::class))
        }

        @Test
        fun `a class cannot be registered under a second domain`() {
            assertThrows<IllegalArgumentException> { system.register<ReplyingActor>("AnotherDomain") }
            // re-registering the same domain is fine
            system.register<ReplyingActor>("ReplyingActor", config = ActorConfig(capacity = 5))
            assertEquals(5, system.get<ReplyingActor>().config.capacity)
        }

        @Test
        fun `replacing a domain's class drops the old class from lookups`() {
            system.register<FirstHandler>("Shared")
            system.register<SecondHandler>("Shared")
            assertEquals(null, system.findName(FirstHandler::class))
            assertFalse(FirstHandler::class in system)
            assertEquals("Shared", system.findName(SecondHandler::class))
            // the old class is free to be registered elsewhere again
            system.register<FirstHandler>("First")
            assertEquals("First", system.findName(FirstHandler::class))
        }

        @Test
        fun `object handlers are rejected with the default factory`() {
            assertThrows<IllegalArgumentException> { system.register<SingletonHandler>("Singleton") }
        }

        @Test
        fun `default factory matches props to constructor parameters`() = runBlocking<Unit> {
            val withLabel = system.actorOfSuspend<PropsActor>(props = Props.of("id" to 7, "label" to "x"))
            val withDefault = system.actorOfSuspend<PropsActor>(props = Props.of("id" to 8))
            assertEquals("7/x", withTimeout(2.seconds) { system.ask<Any>(withLabel, ActorRef.EMPTY, "q").await() })
            assertEquals("8/none", withTimeout(2.seconds) { system.ask<Any>(withDefault, ActorRef.EMPTY, "q").await() })
        }

        @Test
        fun `lambda factory creates handlers without reflection`() = runBlocking<Unit> {
            val lambdaSystem = ActorSystem.createOrGet()
            lambdaSystem.register<PropsActor>("Lambda") { props -> PropsActor(props.get("id"), "lambda") }
            lambdaSystem.start()
            try {
                val ref = lambdaSystem.actorOfSuspend<PropsActor>(props = Props.of("id" to 3))
                assertEquals("3/lambda", withTimeout(2.seconds) { lambdaSystem.ask<Any>(ref, ActorRef.EMPTY, "q").await() })
            } finally {
                lambdaSystem.shutdownGracefully()
            }
        }

        @Test
        fun `suspend factory creates handlers and recreates them on restart`() = runBlocking<Unit> {
            val created = AtomicInteger()
            val suspendSystem = ActorSystem.createOrGet()
            suspendSystem.registerSuspend<GenerationActor>("Generation") { props ->
                delay(10) // creation may suspend
                GenerationActor("${props.get<String>("name")}#${created.incrementAndGet()}")
            }
            val restarted = CompletableDeferred<Unit>()
            suspendSystem += ActorSystemMessageListener {
                if (it.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) restarted.complete(Unit)
            }
            suspendSystem.start()
            try {
                val ref = suspendSystem.actorOfSuspend<GenerationActor>(props = Props.of("name" to "g"))
                assertEquals("g#1", withTimeout(2.seconds) { suspendSystem.ask<Any>(ref, ActorRef.EMPTY, "q").await() })

                // the failure makes the system supervisor restart the actor through the suspend factory
                assertThrows<IllegalStateException> {
                    withTimeout(2.seconds) { suspendSystem.ask<Any>(ref, ActorRef.EMPTY, "boom").await() }
                }
                withTimeout(3.seconds) { restarted.await() }
                assertEquals("g#2", withTimeout(2.seconds) { suspendSystem.ask<Any>(ref, ActorRef.EMPTY, "q").await() })
            } finally {
                suspendSystem.shutdownGracefully()
            }
        }

        @Test
        fun `suspend factory rejects blocking creation`() {
            val factory = object : SuspendActorHandlerFactory {
                override suspend fun <T : ActorHandler> getBeanSuspend(kClass: KClass<T>, props: Props): T =
                    error("not called")
            }
            assertThrows<UnsupportedOperationException> { factory.getBean<GenerationActor>() }
        }
    }

    /** Replies with the generation it was created as; "boom" throws. */
    class GenerationActor(private val generation: String) : ActorHandler {
        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef): Any =
            if (message == "boom") throw IllegalStateException("boom") else generation
    }

    object SingletonHandler : ActorHandler
    class FirstHandler : ActorHandler
    class SecondHandler : ActorHandler
}