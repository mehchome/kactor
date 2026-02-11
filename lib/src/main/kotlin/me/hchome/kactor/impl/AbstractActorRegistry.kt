package me.hchome.kactor.impl

import kotlinx.coroutines.Job
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorRegistry
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.exceptions.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SystemMessage.CreateActor
import me.hchome.kactor.isEmpty
import me.hchome.kactor.isNotEmpty
import kotlin.collections.set
import kotlin.uuid.ExperimentalUuidApi

abstract class AbstractActorRegistry : ActorRegistry {

    protected abstract val actors: MutableMap<ActorRef, Actor>
    protected abstract val runtimeScopes: MutableMap<ActorRef, ActorScope>
    protected abstract val actorChannels: MutableMap<ActorRef, MailBox>
    protected abstract val actorAttributes: MutableMap<ActorRef, Attributes>

    protected lateinit var actorSystem: ActorSystem
        private set
    protected lateinit var systemJob: Job
        private set
    protected lateinit var systemSupervisor: Supervisor
        private set

    override val all: Set<ActorRef>
        get() = actors.keys

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

    @OptIn(ExperimentalUuidApi::class)
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
        return MailBox(config.capacity, config.onBufferOverflow) {
            onUndeliveredMessage(it, ref)
        }
    }

    protected fun createActorRef(message: CreateActor): ActorRef {
        val (id, parentRef, _, _) = message
        return buildActorId(parentRef, id)
    }

    protected fun rebuildChannels(ref: ActorRef) {
        val config = actors[ref]?.domain?.let { actorSystem[it].config } ?: return
        val channel = MailBox(config.capacity, config.onBufferOverflow) {
            onUndeliveredMessage(it, ref)
        }
        actorChannels[ref] = channel
        childReferences(ref).forEach { rebuildChannels(it) }
    }

    protected fun rebuildActorScope(ref: ActorRef) {
        if (ref.isEmpty()) return
        val holder = actors[ref]?.domain?.let { actorSystem[it] } ?: return
        val parentJob = ref.parentJob
        val newRuntimeScope = ActorScopeImpl(parentJob, holder.dispatcher)
        runtimeScopes[ref] = newRuntimeScope
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

        val newHandler = configHolder.newActorHandler()
        val newActor = Actor(
            ref,
            oldActor.domain,
            actorSystem,
            config.supervisorStrategy,
            ref.supervisor,
            targetChannel,
            targetScope,
            newHandler,
            actorAttributes[ref] ?: createAttribute(AttributesImpl()),
            config.idle
        )
        actors[ref] = newActor
        childReferences(ref).forEach { rebuildActors(it) }
    }

    protected fun onUndeliveredMessage(wrapper: ActorEnvelope, ref: ActorRef) {
        val message = wrapper.message
        val sender = wrapper.sender
        val formattedMessage = "Undelivered message: $message"
        actorSystem.notifySystem(
            sender,
            ref,
            formattedMessage,
            ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED
        )
    }

    protected val CreateActor.handler: ActorHandler
        get() = actorSystem[domain].newActorHandler()

    protected val ActorRef.parentJob: Job get() = systemJob

    protected val ActorRef.supervisor: Supervisor
        get() =  systemSupervisor

    protected abstract fun createAttribute(old: Attributes): Attributes
}