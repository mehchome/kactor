@file:Suppress("unused")
package me.hchome.kactor.impl

import kotlinx.coroutines.Job
import me.hchome.kactor.ActorConfig
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorRegistry
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.Props
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.SystemMessage.CreateActor
import me.hchome.kactor.isEmpty
import me.hchome.kactor.isNotEmpty
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

abstract class AbstractActorRegistry : ActorRegistry {

    protected abstract val actors: MutableMap<ActorRef, Actor>
    protected abstract val runtimeScopes: MutableMap<ActorRef, ActorScope>
    protected abstract val actorChannels: MutableMap<ActorRef, MailBox>
    protected abstract val actorAttributes: MutableMap<ActorRef, Attributes>
    protected abstract val actorProps: MutableMap<ActorRef, Props>

    protected lateinit var actorSystem: ActorSystem
        private set
    protected lateinit var systemJob: Job
        private set
    protected lateinit var systemSupervisor: Supervisor
        private set

    override val all: Set<ActorRef>
        get() = actors.keys

    // parent -> children index, so child lookups don't scan every actor and allocate a Set per call
    private val childIndex = ConcurrentHashMap<ActorRef, MutableSet<ActorRef>>()

    /**
     * Live, read-only view of [parent]'s children. It is weakly consistent: safe to iterate while
     * children are being added or removed, but it may or may not reflect those concurrent changes.
     */
    override fun childReferences(parent: ActorRef): Set<ActorRef> = childIndex[parent] ?: emptySet()

    protected fun registerActor(ref: ActorRef, actor: Actor) {
        actors[ref] = actor
        if (ref.hasParent) {
            childIndex.computeIfAbsent(ref.parentOf()) { ConcurrentHashMap.newKeySet() }.add(ref)
        }
    }

    protected fun unregisterActor(ref: ActorRef) {
        actors.remove(ref)
        childIndex.remove(ref)
        if (ref.hasParent) {
            childIndex[ref.parentOf()]?.remove(ref)
        }
    }

    override fun afterInit(
        system: ActorSystem,
        systemJob: Job,
        systemSupervisor: Supervisor
    ) {
        this.actorSystem = system
        this.systemJob = systemJob
        this.systemSupervisor = systemSupervisor
    }

    override fun stopAllActors() {
        actorChannels.keys.forEach { stopActor(it) }
    }

    protected fun getRuntimeScope(ref: ActorRef): ActorScope =
        runtimeScopes[ref] ?: throw ActorSystemException("Actor[$ref] runtime not found")

    protected fun buildActorId(
        parent: ActorRef,
        id: String,
    ): ActorRef = if (parent.isNotEmpty()) {
        parent.childOf(id)
    } else {
        ActorRef.of(id)
    }

    protected fun createChannel(message: CreateActor, ref: ActorRef): MailBox {
        val config = actorSystem[message.domain].config
        return MailBox(config.capacity, config.onBufferOverflow) { envelope, cause ->
            rejectEnvelope(envelope, ref, cause)
        }
    }

    protected fun createActorRef(message: CreateActor): ActorRef {
        val (id, parentRef, _, _) = message
        return buildActorId(parentRef, id)
    }

    protected fun rebuildChannels(ref: ActorRef) {
        val config = actors[ref]?.domain?.let { actorSystem[it].config } ?: return
        val channel = MailBox(config.capacity, config.onBufferOverflow) { envelope, cause ->
            rejectEnvelope(envelope, ref, cause)
        }
        actorChannels[ref] = channel
        childReferences(ref).forEach { rebuildChannels(it) }
    }

    protected fun rebuildActorScope(ref: ActorRef) {
        if (ref.isEmpty()) return
        val holder = actors[ref]?.domain?.let { actorSystem[it] } ?: return
        runtimeScopes[ref]?.cancel()
        runtimeScopes[ref] = ActorScopeImpl(systemJob, holder.dispatcher)
        childReferences(ref).forEach { rebuildActorScope(it) }
    }

    protected fun rebuildAttributeStore(ref: ActorRef) {
        if (ref.isEmpty()) return
        val oldAttribute = actorAttributes[ref] ?: AttributesImpl()
        actorAttributes[ref] = createAttribute(oldAttribute)
        childReferences(ref).forEach { rebuildAttributeStore(it) }
    }

    protected fun rebuildActors(ref: ActorRef) {
        if (ref.isEmpty()) return
        val oldActor = actors[ref] ?: return
        val targetChannel = actorChannels[ref] ?: return
        val targetScope = runtimeScopes[ref] ?: return
        val configHolder = actorSystem[oldActor.domain]
        val config = configHolder.config

        val props = actorProps[ref] ?: Props.EMPTY
        val newHandler = configHolder.newActorHandler(props)
        val newActor = buildActor(
            ref = ref,
            domain = oldActor.domain,
            config = config,
            mailbox = targetChannel,
            runtimeScope = targetScope,
            handler = newHandler,
            attributes = actorAttributes[ref] ?: createAttribute(AttributesImpl()),
            props = props,
        )
        actors[ref] = newActor
        childReferences(ref).forEach { rebuildActors(it) }
    }

    /**
     * Builds an [Actor] wired from its domain [config]. Shared by creation and restart so both
     * paths configure actors identically.
     */
    protected fun buildActor(
        ref: ActorRef,
        domain: String,
        config: ActorConfig,
        mailbox: MailBox,
        runtimeScope: ActorScope,
        handler: ActorHandler,
        attributes: Attributes,
        props: Props,
    ): Actor = Actor(
        ref = ref,
        domain = domain,
        actorSystem = actorSystem,
        supervisorStrategy = config.supervisorStrategy,
        supervisor = ref.supervisor,
        mailbox = mailbox,
        runtimeScope = runtimeScope,
        handler = handler,
        attributes = attributes,
        idle = config.idle,
        props = props,
        supervisionTimeout = config.supervisionTimeout,
    )

    /**
     * Single exit for every envelope that will never reach [ref]'s handler: reports it as
     * undelivered and completes whatever the sender is waiting on, so nothing hangs.
     */
    protected fun rejectEnvelope(envelope: ActorEnvelope, ref: ActorRef, cause: Throwable) {
        when (envelope) {
            is ActorEnvelope.AskActorEnvelope<*> -> envelope.callback.completeExceptionally(cause)
            // The supervisor is gone (stopped or recreated); the failed child should stop too.
            is ActorEnvelope.SuperviseEnvelope -> envelope.callback.complete(SupervisorStrategy.Decision.Stop)
            is ActorEnvelope.SendActorEnvelope -> {}
        }
        actorSystem.notifySystem(
            envelope.sender,
            ref,
            "Message can't be delivered to $ref: ${cause.message}",
            ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED,
            cause,
            envelope.message
        )
    }

    protected val CreateActor.handler: ActorHandler
        get() = actorSystem[domain].newActorHandler(props)

    protected val ActorRef.supervisor: Supervisor
        get() = if (hasParent) actors[parentOf()] ?: systemSupervisor else systemSupervisor

    protected abstract fun createAttribute(old: Attributes): Attributes
}