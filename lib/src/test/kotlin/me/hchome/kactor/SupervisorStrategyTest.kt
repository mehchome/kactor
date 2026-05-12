package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SupervisorStrategyTest {

    // ── Supervisor actor types ───────────────────────────────────────────────
    // Each parent class overrides onSupervise to return a specific decision.
    // The scope (OneForOne vs AllForOne) is set via ActorConfig at registration.

    class ResumeParent : ActorHandler {
        context(context: ActorContext)
        override suspend fun onSupervise(failure: ActorFailure) = SupervisorStrategy.Decision.Resume
    }

    class StopParent : ActorHandler {
        context(context: ActorContext)
        override suspend fun onSupervise(failure: ActorFailure) = SupervisorStrategy.Decision.Stop
    }

    class RestartParent : ActorHandler  // default onSupervise → Restart

    class RecreateParent : ActorHandler {
        context(context: ActorContext)
        override suspend fun onSupervise(failure: ActorFailure) = SupervisorStrategy.Decision.Recreate
    }

    class AllForOneRestartParent : ActorHandler  // default onSupervise → Restart (AllForOne scope)

    class AllForOneStopParent : ActorHandler {
        context(context: ActorContext)
        override suspend fun onSupervise(failure: ActorFailure) = SupervisorStrategy.Decision.Stop
    }

    class EscalateParent : ActorHandler {
        context(context: ActorContext)
        override suspend fun onSupervise(failure: ActorFailure) = SupervisorStrategy.Decision.Escalate
    }

    // Used to prove grandparent is NOT the supervisor of grandchildren.
    // Default onSupervise returns Restart; if a grandchild were to be Stopped
    // despite this, it means the immediate parent (StopParent) handled it correctly.
    class GrandparentActor : ActorHandler

    // ── Child actor ──────────────────────────────────────────────────────────
    // Throws on "fail", completes any CompletableDeferred<Unit> otherwise.

    class FailableActor : ActorHandler {
        context(context: ActorContext)
        @Suppress("UNCHECKED_CAST")
        override suspend fun onMessage(message: Any, sender: ActorRef) {
            when (message) {
                "fail" -> throw RuntimeException("intentional failure")
                is CompletableDeferred<*> -> (message as CompletableDeferred<Unit>).complete(Unit)
            }
        }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────

    private lateinit var system: ActorSystem

    @BeforeEach
    fun setup() {
        system = ActorSystem.createOrGet()
        system.register<ResumeParent>(
            "ResumeParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.OneForOne)
        )
        system.register<StopParent>(
            "StopParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.OneForOne)
        )
        system.register<RestartParent>(
            "RestartParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.OneForOne)
        )
        system.register<RecreateParent>(
            "RecreateParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.OneForOne)
        )
        system.register<AllForOneRestartParent>(
            "AllForOneRestartParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.AllForOne)
        )
        system.register<AllForOneStopParent>(
            "AllForOneStopParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.AllForOne)
        )
        system.register<EscalateParent>(
            "EscalateParent",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.OneForOne)
        )
        system.register<GrandparentActor>(
            "GrandparentActor",
            config = ActorConfig(supervisorStrategy = SupervisorStrategy.OneForOne)
        )
        system.register<FailableActor>(FailableActor::class.simpleName!!)
        system.start()
    }

    @AfterEach
    fun teardown() { system.shutdownGracefully() }

    // Registers a listener and returns a deferred that completes when the
    // given notification arrives for the given ref.
    // Must be installed before triggering the failure.
    private fun awaitNotificationFor(
        type: ActorSystemNotificationMessage.NotificationType,
        ref: ActorRef
    ): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == type && msg.receiver == ref) done.complete(Unit)
        }
        return done
    }

    // ── OneForOne scope tests ────────────────────────────────────────────────

    @Test
    fun `OneForOne Resume - failed actor continues processing, sibling unaffected`() = runBlocking {
        val parent = system.actorOf<ResumeParent>()
        val child1 = system.actorOf<FailableActor>(parent = parent)
        val child2 = system.actorOf<FailableActor>(parent = parent)

        // ACTOR_FATAL fires once onSupervise has returned Resume.
        val fatalHandled = awaitNotificationFor(
            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, child1
        )

        system.send(child1, "fail")
        withTimeout(3.seconds) { fatalHandled.await() }

        // Resume means no restart and no stop — both actors remain alive and responsive.
        val check1 = CompletableDeferred<Unit>()
        val check2 = CompletableDeferred<Unit>()
        system.send(child1, check1)
        system.send(child2, check2)
        withTimeout(2.seconds) { check1.await(); check2.await() }

        assertTrue(child1 in system)
        assertTrue(child2 in system)
    }

    @Test
    fun `OneForOne Stop - only failed child is stopped, sibling survives`() = runBlocking {
        val parent = system.actorOf<StopParent>()
        val child1 = system.actorOf<FailableActor>(parent = parent)
        val child2 = system.actorOf<FailableActor>(parent = parent)

        val child1Destroyed = awaitNotificationFor(
            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, child1
        )

        system.send(child1, "fail")
        withTimeout(3.seconds) { child1Destroyed.await() }

        assertFalse(child1 in system)
        assertTrue(child2 in system)

        val check = CompletableDeferred<Unit>()
        system.send(child2, check)
        withTimeout(2.seconds) { check.await() }
    }

    @Test
    fun `OneForOne Restart - only failed child restarts, sibling is not restarted`() = runBlocking {
        val parent = system.actorOf<RestartParent>()
        val child1 = system.actorOf<FailableActor>(parent = parent)
        val child2 = system.actorOf<FailableActor>(parent = parent)

        val restartedRefs: MutableSet<ActorRef> = ConcurrentHashMap.newKeySet()
        val child1Restarted = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) {
                restartedRefs.add(msg.receiver)
                if (msg.receiver == child1) child1Restarted.complete(Unit)
            }
        }

        system.send(child1, "fail")
        withTimeout(3.seconds) { child1Restarted.await() }

        assertTrue(child1 in restartedRefs)
        assertFalse(child2 in restartedRefs, "sibling should not be restarted under OneForOne")

        val check1 = CompletableDeferred<Unit>()
        val check2 = CompletableDeferred<Unit>()
        system.send(child1, check1)
        system.send(child2, check2)
        withTimeout(2.seconds) { check1.await(); check2.await() }
    }

    @Test
    fun `OneForOne Recreate - only failed child is recreated, sibling unaffected`() = runBlocking {
        val parent = system.actorOf<RecreateParent>()
        val child1 = system.actorOf<FailableActor>(parent = parent)
        val child2 = system.actorOf<FailableActor>(parent = parent)

        val restartedRefs: MutableSet<ActorRef> = ConcurrentHashMap.newKeySet()
        val child1Recreated = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) {
                restartedRefs.add(msg.receiver)
                if (msg.receiver == child1) child1Recreated.complete(Unit)
            }
        }

        system.send(child1, "fail")
        withTimeout(3.seconds) { child1Recreated.await() }

        assertTrue(child1 in restartedRefs)
        assertFalse(child2 in restartedRefs, "sibling should not be recreated under OneForOne")

        val check1 = CompletableDeferred<Unit>()
        val check2 = CompletableDeferred<Unit>()
        system.send(child1, check1)
        system.send(child2, check2)
        withTimeout(2.seconds) { check1.await(); check2.await() }
    }

    // ── AllForOne scope tests ────────────────────────────────────────────────

    @Test
    fun `AllForOne Restart - all siblings restart when one fails`() = runBlocking {
        val parent = system.actorOf<AllForOneRestartParent>()
        val child1 = system.actorOf<FailableActor>(parent = parent)
        val child2 = system.actorOf<FailableActor>(parent = parent)

        val restartedRefs: MutableSet<ActorRef> = ConcurrentHashMap.newKeySet()
        val bothRestarted = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) {
                restartedRefs.add(msg.receiver)
                if (child1 in restartedRefs && child2 in restartedRefs) bothRestarted.complete(Unit)
            }
        }

        system.send(child1, "fail")
        withTimeout(3.seconds) { bothRestarted.await() }

        assertTrue(child1 in restartedRefs)
        assertTrue(child2 in restartedRefs)
    }

    @Test
    fun `AllForOne Stop - all siblings stop when one fails`() = runBlocking {
        val parent = system.actorOf<AllForOneStopParent>()
        val child1 = system.actorOf<FailableActor>(parent = parent)
        val child2 = system.actorOf<FailableActor>(parent = parent)

        val destroyedRefs: MutableSet<ActorRef> = ConcurrentHashMap.newKeySet()
        val bothDestroyed = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED) {
                destroyedRefs.add(msg.receiver)
                if (child1 in destroyedRefs && child2 in destroyedRefs) bothDestroyed.complete(Unit)
            }
        }

        system.send(child1, "fail")
        withTimeout(3.seconds) { bothDestroyed.await() }

        assertFalse(child1 in system)
        assertFalse(child2 in system)
    }

    // ── Escalate test ────────────────────────────────────────────────────────

    @Test
    fun `Escalate - failure escalates through parent to system, system restarts parent`() = runBlocking {
        // EscalateParent returns Escalate from onSupervise, which causes the failure to
        // propagate to EscalateParent's own supervisor (the actor system).
        // The system always restarts, so EscalateParent itself is restarted.
        val parent = system.actorOf<EscalateParent>()
        val child = system.actorOf<FailableActor>(parent = parent)

        val parentRestarted = awaitNotificationFor(
            ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, parent
        )

        system.send(child, "fail")
        withTimeout(3.seconds) { parentRestarted.await() }

        assertTrue(parent in system)
    }

    // ── System-level supervisor identity tests ───────────────────────────────

    @Test
    fun `actor system is supervisor for top-level actors with no parent`() = runBlocking {
        // A top-level actor (no parent) is supervised directly by the actor system.
        // The system always restarts on failure, so we expect ACTOR_RESTARTED.
        val topLevel = system.actorOf<FailableActor>()
        assertTrue(topLevel.parentOf().isEmpty(), "top-level actor must have no parent ref")

        val restarted = awaitNotificationFor(
            ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED, topLevel
        )

        system.send(topLevel, "fail")
        withTimeout(3.seconds) { restarted.await() }

        assertTrue(topLevel in system)
    }

    @Test
    fun `immediate parent not grandparent is supervisor for a grandchild`() = runBlocking {
        // Hierarchy:  grandparent (GrandparentActor, default → Restart)
        //                └── parent (StopParent, onSupervise → Stop)
        //                        └── grandchild (FailableActor)
        //
        // When grandchild fails, StopParent (the immediate parent) handles it and
        // returns Stop → grandchild is destroyed.
        // If GrandparentActor were the supervisor instead it would return Restart,
        // so observing ACTOR_DESTROYED (not ACTOR_RESTARTED) proves the correct supervisor.
        val grandparent = system.actorOf<GrandparentActor>()
        val parent      = system.actorOf<StopParent>(parent = grandparent)
        val grandchild  = system.actorOf<FailableActor>(parent = parent)

        assertEquals(parent,      grandchild.parentOf(), "grandchild's parent ref must be parent")
        assertEquals(grandparent, parent.parentOf(),     "parent's parent ref must be grandparent")

        val grandchildDestroyed = awaitNotificationFor(
            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED, grandchild
        )

        system.send(grandchild, "fail")
        withTimeout(3.seconds) { grandchildDestroyed.await() }

        // StopParent handled it: grandchild is gone, parent and grandparent are alive.
        assertFalse(grandchild in system)
        assertTrue(parent      in system)
        assertTrue(grandparent in system)
    }

    @Test
    fun `system OneForOne - only the failing top-level actor is restarted`() = runBlocking {
        // Two sibling top-level actors: when one fails the system (OneForOne by default)
        // must restart only that actor and leave the other untouched.
        val topLevel1 = system.actorOf<FailableActor>()
        val topLevel2 = system.actorOf<FailableActor>()

        val restartedRefs: MutableSet<ActorRef> = ConcurrentHashMap.newKeySet()
        val topLevel1Restarted = CompletableDeferred<Unit>()
        system += ActorSystemMessageListener { msg ->
            if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) {
                restartedRefs.add(msg.receiver)
                if (msg.receiver == topLevel1) topLevel1Restarted.complete(Unit)
            }
        }

        system.send(topLevel1, "fail")
        withTimeout(3.seconds) { topLevel1Restarted.await() }

        assertTrue(topLevel1  in restartedRefs)
        assertFalse(topLevel2 in restartedRefs, "system OneForOne must not restart sibling top-level actors")

        val check = CompletableDeferred<Unit>()
        system.send(topLevel2, check)
        withTimeout(2.seconds) { check.await() }
    }

    @Test
    fun `system AllForOne - all top-level actors restart when one fails`() = runBlocking {
        // Create a dedicated system with AllForOne strategy so that a single top-level
        // actor failure causes every other top-level actor to be restarted as well.
        val allForOneSystem = ActorSystem.createOrGet(strategy = SupervisorStrategy.AllForOne)
        allForOneSystem.register<FailableActor>(FailableActor::class.simpleName!!)
        allForOneSystem.start()

        try {
            val topLevel1 = allForOneSystem.actorOf<FailableActor>()
            val topLevel2 = allForOneSystem.actorOf<FailableActor>()

            val restartedRefs: MutableSet<ActorRef> = ConcurrentHashMap.newKeySet()
            val bothRestarted = CompletableDeferred<Unit>()
            allForOneSystem += ActorSystemMessageListener { msg ->
                if (msg.type == ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED) {
                    restartedRefs.add(msg.receiver)
                    if (topLevel1 in restartedRefs && topLevel2 in restartedRefs) {
                        bothRestarted.complete(Unit)
                    }
                }
            }

            allForOneSystem.send(topLevel1, "fail")
            withTimeout(3.seconds) { bothRestarted.await() }

            assertTrue(topLevel1 in restartedRefs)
            assertTrue(topLevel2 in restartedRefs)
        } finally {
            allForOneSystem.shutdownGracefully()
        }
    }
}