package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.hchome.kactor.impl.ActorEnvelope
import me.hchome.kactor.impl.MailBox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MailBoxTest {

    private fun envelope(message: Any) = ActorEnvelope.SendActorEnvelope(message, ActorRef.EMPTY)

    private class Rejections {
        val items = mutableListOf<Pair<Any, Throwable>>()
        val handler: (ActorEnvelope, Throwable) -> Unit = { env, cause -> items += env.message to cause }
    }

    // ── MailBox unit tests ───────────────────────────────────────────────────

    @Nested
    inner class Overflow {
        @Test
        fun `SUSPEND rejects the new message when full`() {
            val rejected = Rejections()
            val mailbox = MailBox(1, BufferOverflow.SUSPEND, rejected.handler)

            assertTrue(mailbox.offer(envelope("a"), MessagePriority.NORMAL))
            assertFalse(mailbox.offer(envelope("b"), MessagePriority.NORMAL))

            assertEquals(listOf<Any>("b"), rejected.items.map { it.first })
            assertIs<MailboxFullException>(rejected.items.single().second)
            assertEquals("a", mailbox.lowPriorityChannel.tryReceive().getOrThrow().message)
        }

        @Test
        fun `DROP_LATEST rejects the new message instead of silently dropping it`() {
            val rejected = Rejections()
            val mailbox = MailBox(1, BufferOverflow.DROP_LATEST, rejected.handler)

            assertTrue(mailbox.offer(envelope("a"), MessagePriority.NORMAL))
            assertFalse(mailbox.offer(envelope("b"), MessagePriority.NORMAL))

            assertEquals(listOf<Any>("b"), rejected.items.map { it.first })
            assertIs<MailboxFullException>(rejected.items.single().second)
            assertEquals("a", mailbox.lowPriorityChannel.tryReceive().getOrThrow().message)
        }

        @Test
        fun `DROP_OLDEST evicts and rejects the oldest message`() {
            val rejected = Rejections()
            val mailbox = MailBox(1, BufferOverflow.DROP_OLDEST, rejected.handler)

            assertTrue(mailbox.offer(envelope("a"), MessagePriority.NORMAL))
            assertTrue(mailbox.offer(envelope("b"), MessagePriority.NORMAL))

            assertEquals(listOf<Any>("a"), rejected.items.map { it.first })
            assertIs<MailboxFullException>(rejected.items.single().second)
            assertEquals("b", mailbox.lowPriorityChannel.tryReceive().getOrThrow().message)
        }

        @Test
        fun `control channel is unbounded and independent of user capacity`() {
            val rejected = Rejections()
            val mailbox = MailBox(1, BufferOverflow.DROP_OLDEST, rejected.handler)
            mailbox.offer(envelope("user-high"), MessagePriority.HIGH)

            repeat(1_000) { assertTrue(mailbox.offerControl(envelope(it))) }

            assertTrue(rejected.items.isEmpty())
            assertEquals("user-high", mailbox.highPriorityChannel.tryReceive().getOrThrow().message)
        }

        @Test
        fun `non-positive capacity is refused`() {
            assertThrows<IllegalArgumentException> { MailBox(0, BufferOverflow.SUSPEND) { _, _ -> } }
            assertThrows<IllegalArgumentException> { ActorConfig(capacity = 0) }
        }
    }

    @Nested
    inner class Cancel {
        @Test
        fun `cancel rejects every buffered message as closed`() {
            val rejected = Rejections()
            val mailbox = MailBox(10, BufferOverflow.SUSPEND, rejected.handler)
            mailbox.offerControl(envelope("c"))
            mailbox.offer(envelope("h"), MessagePriority.HIGH)
            mailbox.offer(envelope("l"), MessagePriority.NORMAL)

            mailbox.cancel()

            assertEquals(setOf<Any>("c", "h", "l"), rejected.items.map { it.first }.toSet())
            assertTrue(rejected.items.all { it.second is MailboxClosedException })
        }

        @Test
        fun `offer after cancel is rejected as closed`() {
            val rejected = Rejections()
            val mailbox = MailBox(10, BufferOverflow.SUSPEND, rejected.handler)
            mailbox.cancel()

            assertFalse(mailbox.offer(envelope("late"), MessagePriority.NORMAL))
            assertFalse(mailbox.offerControl(envelope("late-control")))

            assertEquals(listOf<Any>("late", "late-control"), rejected.items.map { it.first })
            assertTrue(rejected.items.all { it.second is MailboxClosedException })
        }
    }

    // ── Integration: rejected asks never hang ────────────────────────────────

    // Signals [started] once it is inside onMessage, then waits for [gate].
    data class Block(val started: CompletableDeferred<Unit>, val gate: CompletableDeferred<Unit>)

    class BlockingActor : ActorHandler {
        context(context: ActorContext)
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            if (message is Block) {
                message.started.complete(Unit)
                message.gate.await()
            }
        }

        context(context: ActorContext)
        override suspend fun onAsk(message: Any, sender: ActorRef): Any = message
    }

    @Nested
    inner class RejectedAsk {
        private lateinit var system: ActorSystem

        @BeforeEach
        fun setup() {
            system = ActorSystem.createOrGet()
            system.register<BlockingActor>("BlockingActor", config = ActorConfig(capacity = 1))
            system.start()
        }

        @AfterEach
        fun teardown() { system.shutdownGracefully() }

        // Blocks the actor inside onMessage, so later messages stay queued in its mailbox.
        private suspend fun blockedActor(): Pair<ActorRef, CompletableDeferred<Unit>> {
            val ref = system.actorOfSuspend<BlockingActor>()
            val block = Block(CompletableDeferred(), CompletableDeferred())
            system.send(ref, block)
            withTimeout(2.seconds) { block.started.await() }
            return ref to block.gate
        }

        @Test
        fun `ask to a full mailbox fails immediately with MailboxFullException`() = runBlocking<Unit> {
            val (ref, gate) = blockedActor()

            val queued = system.ask<Any>(ref, ActorRef.EMPTY, "queued")
            val overflow = system.ask<Any>(ref, ActorRef.EMPTY, "overflow")

            assertThrows<MailboxFullException> { withTimeout(1.seconds) { overflow.await() } }

            gate.complete(Unit)
            assertEquals("queued", withTimeout(2.seconds) { queued.await() })
        }

        @Test
        fun `queued ask fails with MailboxClosedException when the actor is stopped`() = runBlocking<Unit> {
            val (ref, _) = blockedActor()
            val destroyed = CompletableDeferred<Unit>()
            system += ActorSystemMessageListener { msg ->
                if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED && msg.receiver == ref) {
                    destroyed.complete(Unit)
                }
            }

            val pending = system.ask<Any>(ref, ActorRef.EMPTY, "never processed")
            system.destroyActor(ref)
            withTimeout(2.seconds) { destroyed.await() }

            assertThrows<MailboxClosedException> { withTimeout(1.seconds) { pending.await() } }
        }
    }
}