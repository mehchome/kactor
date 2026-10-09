package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FailureCodeTest {

    // Stops the child on BIZ_STOP or a failed preStart, resumes it otherwise; records every code it sees.
    class CodeParent : ActorHandler {
        context(context: ActorContext)
        override suspend fun onSupervise(failure: ActorFailure): SupervisorStrategy.Decision {
            codes.add(failure.code)
            return when (failure.code) {
                BIZ_STOP, ActorInitializationException.CODE -> SupervisorStrategy.Decision.Stop
                else -> SupervisorStrategy.Decision.Resume
            }
        }
    }

    class CodeChild : ActorHandler {
        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            when (message) {
                "biz" -> throw ActorException(BIZ_STOP, "business failure")
                "plain" -> throw IllegalStateException("plain failure")
                is CompletableDeferred<*> -> (message as CompletableDeferred<Unit>).complete(Unit)
            }
        }
    }

    class FailingStartChild : ActorHandler {
        context(context: ActorContext)
        override suspend fun preStart() = throw IllegalStateException("cannot start")
    }

    companion object {
        const val BIZ_STOP = "BIZ_STOP"
        val codes = ConcurrentLinkedQueue<String>()
    }

    private lateinit var system: ActorSystem
    private val notifications = ConcurrentLinkedQueue<ActorSystemNotificationMessage>()

    @BeforeEach
    fun setup() {
        codes.clear()
        system = ActorSystem.createOrGet()
        system.register<CodeParent>("CodeParent")
        system.register<CodeChild>("CodeChild")
        system.register<FailingStartChild>("FailingStartChild")
        system += ActorSystemMessageListener { notifications.add(it) }
        system.start()
    }

    @AfterEach
    fun teardown() { system.shutdownGracefully() }

    private suspend fun awaitNotification(
        type: ActorSystemNotificationMessage.NotificationType,
        ref: ActorRef
    ): ActorSystemNotificationMessage = withTimeout(3.seconds) {
        var found: ActorSystemNotificationMessage? = null
        while (found == null) {
            found = notifications.firstOrNull { it.type == type && it.receiver == ref }
            if (found == null) delay(10)
        }
        found
    }

    @Test
    fun `ActorException code drives the supervision decision`() = runBlocking<Unit> {
        val parent = system.actorOfSuspend<CodeParent>()
        val child = system.actorOfSuspend<CodeChild>(parent = parent)

        system.send(child, "biz")
        awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, child)

        assertEquals(listOf(BIZ_STOP), codes.toList())
        val fatal = awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, child)
        assertTrue(fatal.message.contains("[$BIZ_STOP]"))
        assertFalse(child in system)
    }

    @Test
    fun `non ActorException maps to UNKNOWN_CODE`() = runBlocking<Unit> {
        val parent = system.actorOfSuspend<CodeParent>()
        val child = system.actorOfSuspend<CodeChild>(parent = parent)

        system.send(child, "plain")
        awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, child)

        // Resumed: the child keeps processing messages, which also means onSupervise has returned.
        val check = CompletableDeferred<Unit>()
        system.send(child, check)
        withTimeout(2.seconds) { check.await() }

        assertEquals(listOf(ActorFailure.UNKNOWN_CODE), codes.toList())
        assertTrue(child in system)
    }

    @Test
    fun `failed preStart is reported with ActorInitializationException CODE`() = runBlocking<Unit> {
        val parent = system.actorOfSuspend<CodeParent>()
        val child = system.actorOfSuspend<FailingStartChild>(parent = parent)

        awaitNotification(ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, child)

        assertEquals(listOf(ActorInitializationException.CODE), codes.toList())
    }
}
