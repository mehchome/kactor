package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
    }

    object SingletonHandler : ActorHandler
    class FirstHandler : ActorHandler
    class SecondHandler : ActorHandler
}