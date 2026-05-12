package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ActorSystemTest {

    // ── Test handlers ──────────────────────────────────────────────────────

    // Completes any CompletableDeferred<String> it receives as a message
    class EchoActor : ActorHandler {
        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            (message as? CompletableDeferred<String>)?.complete("ok")
        }
    }

    // Responds to any ask with "pong"
    class PingActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef, callback: CompletableDeferred<in Any>) {
            callback.complete("pong")
        }
    }

    // Throws on "fail"; completes CompletableDeferred<Unit> on any other message
    class FailActor : ActorHandler {
        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            when (message) {
                "fail" -> throw RuntimeException("intentional failure")
                is CompletableDeferred<*> -> (message as CompletableDeferred<Unit>).complete(Unit)
            }
        }
    }

    // Stores the first CompletableDeferred<Unit> it receives, completes it on idle
    class IdleActor : ActorHandler {
        private var signal: CompletableDeferred<Unit>? = null

        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            signal = message as? CompletableDeferred<Unit>
        }

        context(context: ActorContext)
        override suspend fun onIdle() {
            signal?.complete(Unit)
            context.stopSelf()
        }
    }

    // ── Fixture ────────────────────────────────────────────────────────────

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.createOrGet()
        system.register<EchoActor>(EchoActor::class.simpleName!!)
        system.register<PingActor>(PingActor::class.simpleName!!)
        system.register<FailActor>(FailActor::class.simpleName!!)
        system.register<IdleActor>(IdleActor::class.simpleName!!, config = ActorConfig(idle = 300.milliseconds))
        system.start()
    }

    @AfterEach
    fun teardown() {
        system.shutdownGracefully()
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    fun `tell delivers message to actor`() = runBlocking {
        val ref = system.actorOf<EchoActor>()
        val received = CompletableDeferred<String>()
        system.send(ref, received)
        assertEquals("ok", withTimeout(2.seconds) { received.await() })
    }

    @Test
    fun `ask returns actor response`() = runBlocking {
        val ref = system.actorOf<PingActor>()
        val result = withTimeout(2.seconds) {
            system.ask<String>(ref, ActorRef.EMPTY, "ping").await()
        }
        assertEquals("pong", result)
    }

    @Test
    fun `stopping parent removes all descendants from registry`() = runBlocking {
        // cancelAndCleanup runs before notifySystem, so by the time ACTOR_DESTROYED
        // fires all three refs are already out of the actors map
        val parentDestroyed = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED) {
                parentDestroyed.complete(Unit)
            }
        }

        val parent = system.actorOf<EchoActor>()
        val child = system.actorOf<EchoActor>(parent = parent)
        val grandchild = system.actorOf<EchoActor>(parent = child)

        system.destroyActor(parent)
        withTimeout(3.seconds) { parentDestroyed.await() }

        assertFalse(parent in system)
        assertFalse(child in system)
        assertFalse(grandchild in system)
    }

    @Test
    fun `actor restarts after failure and processes subsequent messages`() = runBlocking {
        val restarted = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) {
                restarted.complete(Unit)
            }
        }

        val ref = system.actorOf<FailActor>()
        system.send(ref, "fail")
        withTimeout(3.seconds) { restarted.await() }

        val alive = CompletableDeferred<Unit>()
        system.send(ref, alive)
        withTimeout(2.seconds) { alive.await() }
    }

    @Test
    fun `idle timeout triggers onIdle`() = runBlocking {
        val ref = system.actorOf<IdleActor>()
        val idled = CompletableDeferred<Unit>()
        system.send(ref, idled)
        withTimeout(2.seconds) { idled.await() }
    }

    @Test
    fun `message to unknown actor triggers undelivered notification`() = runBlocking {
        val notified = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED) {
                notified.complete(Unit)
            }
        }
        system.send(ActorRef.of("ghost"), "hello")
        withTimeout(2.seconds) { notified.await() }
    }

    @Test
    fun `childReferences returns only direct children not grandchildren`() = runBlocking {
        val parent = system.actorOf<EchoActor>()
        val child = system.actorOf<EchoActor>(parent = parent)
        val grandchild = system.actorOf<EchoActor>(parent = child)

        val parentChildren = system.childReferences(parent)
        val childChildren = system.childReferences(child)

        assertTrue(child in parentChildren)
        assertFalse(grandchild in parentChildren)
        assertTrue(grandchild in childChildren)

        system.destroyActor(parent)
    }

    @Test
    fun `multiple actors receive messages independently`() = runBlocking {
        val ref1 = system.actorOf<EchoActor>()
        val ref2 = system.actorOf<EchoActor>()
        val d1 = CompletableDeferred<String>()
        val d2 = CompletableDeferred<String>()
        system.send(ref1, d1)
        system.send(ref2, d2)
        withTimeout(2.seconds) {
            assertEquals("ok", d1.await())
            assertEquals("ok", d2.await())
        }
    }
}
