package me.hchome.kactor.impl

import kotlinx.coroutines.channels.Channel
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SystemMessage
import me.hchome.kactor.UserMessage
import me.hchome.kactor.isEmpty
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.remove

internal class LocalActorRegistry : AbstractActorRegistry() {
    override val actors = ConcurrentHashMap<ActorRef, Actor>()
    override val runtimeScopes = ConcurrentHashMap<ActorRef, ActorScope>()
    override val actorChannels = ConcurrentHashMap<ActorRef, Channel<ActorEnvelope>>()
    override val actorAttributes = ConcurrentHashMap<ActorRef, Attributes>()

    override suspend fun tell(tell: UserMessage.Tell) {
        val (target, sender, message) = tell
        actors[target]?.also { actor ->
            actor.send(message, sender)
        }
    }

    override suspend fun ask(ask: UserMessage.Ask) {
        val (target, sender, message, callback) = ask
        actors[target]?.also { actor ->
            actor.ask(message, sender, callback)
        }
    }

    override fun createActor(message: SystemMessage.CreateActor) {
        val (_, _, isSingleton, _, callback) = message
        val configHolder = actorSystem[message.domain]
        val (_, dispatcher, config, _, _) = configHolder
        val ref = createActorRef(message)
        if (ref in actors.keys) {
            callback.completeExceptionally(ActorSystemException("Actor[$ref] already exists"))
            return
        }
        val parentJob = ref.parentJob
        val supervisor: Supervisor = ref.supervisor
        // new actor's environment
        val newRuntimeScope = ActorScopeImpl(parentJob, dispatcher)
        val newMailbox = createChannel(message, ref)
        val newHandler = message.handler
        val newAttributes = AttributesImpl()
        val newActor = Actor(
            ref, isSingleton, message.domain, actorSystem, config.supervisorStrategy,
            supervisor, newMailbox, newRuntimeScope, newHandler, newAttributes
        )
        // store all actor information
        actors[ref] = newActor
        runtimeScopes[ref] = newRuntimeScope
        actorChannels[ref] = newMailbox
        actorAttributes[ref] = newAttributes
        // start an actor
        try {
            newActor.startActor()
            callback.complete(ref)
            actorSystem.notifySystem(ActorRef.EMPTY, ref, "Actor[$ref] created", ActorSystemNotificationMessage.NotificationType.ACTOR_CREATED)
        } catch (e: Throwable) {
            callback.completeExceptionally(e)
        }
    }

    override fun stopActor(ref: ActorRef) {
        if (ref.isEmpty()) return
        childReferences(ref).forEach {
            stopActor(it)
        }
        actorChannels.remove(ref)?.close()
        actorAttributes.remove(ref)
        runtimeScopes.remove(ref)?.cancel()
        actors.remove(ref)
        actorSystem.notifySystem(ActorRef.EMPTY, ref, "Actor[$ref] stopped", ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED)
    }

    override fun restartActor(ref: ActorRef) {
        if (ref.isEmpty()) return
        actors[ref] ?: return
        val runtimeScope = runtimeScopes[ref] ?: return
        closeChannels(ref)
        runtimeScope.cancel()
        // rebuild actor environment
        rebuildChannels(ref)
        rebuildActorScope(ref)
        rebuildAttributeStore(ref)
        // rebuild actor objects
        rebuildActors(ref)
        // start actor again
        startActorRecursive(ref)
    }

    override fun createAttribute(old: Attributes): Attributes {
        return AttributesImpl(old)
    }

    private fun closeChannels(ref: ActorRef) {
        val childRefs = childReferences(ref)
        childRefs.forEach { closeChannels(it) }
        actorChannels[ref]?.close()
    }


    private fun startActorRecursive(ref: ActorRef) {
        actors[ref]?.startActor()
        actorSystem.notifySystem(ActorRef.EMPTY, ref, "Actor[$ref] restarted", ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED)
        childReferences(ref).forEach { startActorRecursive(it) }
    }
}