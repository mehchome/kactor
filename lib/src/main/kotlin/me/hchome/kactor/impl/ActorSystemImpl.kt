@file:Suppress("unused")

package me.hchome.kactor.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import me.hchome.kactor.ActorFailure
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorHandlerConfigHolder
import me.hchome.kactor.ActorHandlerFactory
import me.hchome.kactor.ActorHandlerRegistry
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.SystemMessage
import me.hchome.kactor.SystemMessage.*
import me.hchome.kactor.UserMessage
import me.hchome.kactor.isNullOrEmpty
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Actor system implementation
 */
internal class ActorSystemImpl(
    handlerFactory: ActorHandlerFactory,
    private val supervisorStrategy: SupervisorStrategy
) : ActorSystem,
    Supervisor,
    ActorHandlerRegistry by ActorHandlerRegistryImpl(Dispatchers.Default, handlerFactory) {
    private val systemJob = SupervisorJob()
    private val systemScope = CoroutineScope(systemJob + Dispatchers.Default)
    private val systemMailbox: Channel<SystemMessage> = Channel(64)
    private val userMailbox: Channel<UserMessage> = Channel(1024)

    private val actors: MutableMap<ActorRef, Actor> = mutableMapOf()
    private val runtimeScopes: MutableMap<ActorRef, ActorScope> = mutableMapOf()
    private val actorChannels: MutableMap<ActorRef, Channel<ActorEnvelope>> = mutableMapOf()
    private val actorConfigHolders: MutableMap<ActorRef, ActorHandlerConfigHolder> = mutableMapOf()
    private val actorAttributes: MutableMap<ActorRef, Attributes> = mutableMapOf()

    val all: Set<ActorRef> get() = actors.keys.toSet()

    private var mailboxJob: Job? = null

    internal val _notifications = MutableSharedFlow<ActorSystemNotificationMessage>(0, 64, BufferOverflow.DROP_OLDEST)
    override val notifications: Flow<ActorSystemNotificationMessage>
        get() = _notifications

//    override fun dispose(): Unit = runBlocking {
//        systemJob.cancelAndJoin()
//        clear()
//        systemScope.cancel()
//    }

    override fun contains(ref: ActorRef): Boolean = actors.contains(ref)

    override fun childReferences(parent: ActorRef): Set<ActorRef> = actors.keys.filter(parent::isParentOf).toSet()

    override suspend fun processFailure(
        ref: ActorRef,
        decision: SupervisorStrategy.Decision
    ) {
        TODO("Not yet implemented")
    }

    override suspend fun <T : ActorHandler> actorOfSuspend(
        id: String?,
        parent: ActorRef,
        kClass: KClass<T>
    ): ActorRef {
        TODO()
//        actorOfSuspend(id, false, parent, kClass)
    }

    override suspend fun <T> serviceOfSuspend(
        kClass: KClass<T>
    ): ActorRef where T : ActorHandler {
        TODO()
//        actorOfSuspend(null, true, ActorRef.Companion.EMPTY, kClass)
    }

    override fun getServices(): Set<ActorRef> = actors.filter { it.value.singleton }.keys.toSet()

    override fun getService(kClass: KClass<out ActorHandler>): ActorRef = actors.firstNotNullOf { (key, actor) ->
        if (actor.singleton && ActorRef.ofService(kClass) == actor.ref) key else ActorRef.EMPTY
    }

    suspend fun recover(child: ActorRef, attributes: Attributes) {
//        this[child].recover(attributes)
    }

    suspend fun snapshot(child: ActorRef): Attributes? {
//        return try {
////            this[child].snapshot()
//        } catch (_: Throwable) {
//            null
//        }
        TODO()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        mailboxJob?.cancel()
        mailboxJob = systemScope.launch {
            try {
                while (isActive) {
                    systemMailbox.tryReceive().getOrNull()?.also {
                        handleSystemMessage(it)
                        continue
                    }
                    select {
                        systemMailbox.onReceive(::handleSystemMessage)
                        userMailbox.onReceive(::handleUserMessage)
                    }
                }
            } catch (e: CancellationException) {
                LOGGER.debug("Actor system mailbox job cancelled")
                throw e
            }
        }
    }

    override fun shutdownGracefully() = runBlocking {
        systemMailbox.close()
        mailboxJob?.cancelAndJoin()
        systemJob.cancelAndJoin()
        systemScope.cancel()
    }

    override fun destroyActor(actorRef: ActorRef) {
//        this.remove(actorRef)?.dispose()
//        notifySystem(
//            actorRef,
//            ActorRef.EMPTY,
//            "Actor destroyed",
//            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED
//        )
    }

    override fun send(actorRef: ActorRef, sender: ActorRef, message: Any) {
//        this[actorRef].send(message, sender)
    }

    override fun <T : Any> ask(actorRef: ActorRef, sender: ActorRef, message: Any, timeout: Duration): Deferred<T> {

//        systemScope.async {
//            val actor = this@ActorSystemImpl[actorRef]
//            val deferred = CompletableDeferred<T>()
//            try {
//                withTimeout(timeout) {
//                    actor.ask<T>(message, sender, deferred)
//                    deferred.await()
//                }
//            } catch (e: TimeoutCancellationException) {
//                notifySystem(
//                    sender,
//                    actorRef,
//                    "Actor timeout",
//                    ActorSystemNotificationMessage.NotificationType.ACTOR_TIMEOUT,
//                )
//                throw ActorSystemException("Actor timeout")
//            }
//        }

        TODO()
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun buildActorId(
        parent: ActorRef?,
        id: String?,
        singleton: Boolean,
        kClass: KClass<*>
    ): String {
        if (!id.isNullOrBlank() && id.contains('/')) {
            throw ActorSystemException("Actor id cannot contain '/'")
        }
        val baseId = when {
            id.isNullOrBlank() && !singleton -> "actor-${Uuid.random()}"
            id.isNullOrBlank() -> "$kClass"
            else -> id
        }
        return if (!parent.isNullOrEmpty()) {
            "${parent.actorId}/$baseId"
        } else {
            baseId
        }
    }

    override fun notifySystem(
        sender: ActorRef,
        receiver: ActorRef,
        message: String,
        notificationType: ActorSystemNotificationMessage.NotificationType,
        throwable: Throwable?
    ) {
//        val level = when (notificationType) {
//            ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED,
//            ActorSystemNotificationMessage.NotificationType.ACTOR_TIMEOUT,
//            ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION,
//            ActorSystemNotificationMessage.NotificationType.ACTOR_TASK_EXCEPTION
//                -> ActorSystemNotificationMessage.MessageLevel.WARN
//
//            ActorSystemNotificationMessage.NotificationType.ACTOR_MESSAGE,
//            ActorSystemNotificationMessage.NotificationType.ACTOR_CREATED,
//            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED -> ActorSystemNotificationMessage.MessageLevel.INFO
//
//            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL -> ActorSystemNotificationMessage.MessageLevel.ERROR
//        }
//        val notification = ActorSystemNotificationMessage(sender, receiver, level, message, throwable)
//        _notifications.tryEmit(notification)
    }

    override suspend fun supervise(
        child: ActorRef,
        sender: ActorRef,
        message: Any,
        cause: Throwable
    ) {
        when(supervisorStrategy) {
            is SupervisorStrategy.AllForOne, is SupervisorStrategy.Escalate -> { // System cannot do crazy thing just fall back to OneForOne
                SupervisorStrategy.OneForOne.decide(ActorFailure(this, child, sender, message, cause, this))
            }
            else -> {
                supervisorStrategy.onFailure(ActorFailure(this, child, sender, message, cause, this))
            }
        }
    }

    private suspend fun handleSystemMessage(message: SystemMessage): Unit = when (message) {
        is CreateActor -> handleCreateActor(message)
        else -> {}
    }

    private suspend fun handleUserMessage(message: UserMessage): Unit = when (message) {
        else -> {}
    }

    private fun handleCreateActor(message: CreateActor) {
        val (ref, isSingleton, domain) = message
        val configHolder = this[domain]
        val (_, dispatcher, config, factory, kClass) = configHolder

        val parentJob = if (ref.hasParent) {
            getRuntimeScope(ref.parentOf()).actorJob
        } else {
            systemJob
        }
        val supervisor: Supervisor = actors[ref.parentOf()] ?: this


        // new actor's environment
        val newRuntimeScope = ActorScopeImpl(parentJob, dispatcher)
        val newMailbox = Channel<ActorEnvelope>(config.capacity, config.onBufferOverflow) { envelope ->
            onUndeliveredMessage(envelope, ref)
        }
        val newHandler = factory.getBean(kClass)
        val newAttributes = AttributesImpl()
        val newActor = Actor(
            ref, isSingleton, this, config.supervisorStrategy,
            supervisor, newMailbox, newRuntimeScope, newHandler, newAttributes
        )

        // start an actor
        newActor.startActor()

        // store all actor information
        actors[ref] = newActor
        runtimeScopes[ref] = newRuntimeScope
        actorChannels[ref] = newMailbox
        actorConfigHolders[ref] = configHolder
        actorAttributes[ref] = newAttributes
    }

    private fun onUndeliveredMessage(wrapper: ActorEnvelope, ref: ActorRef) {
        val message = wrapper.message
        val sender = wrapper.sender
        val formattedMessage = "Undelivered message: $message"
        notifySystem(
            sender,
            ActorRef.EMPTY,
            formattedMessage,
            ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED
        )
    }

    private fun getRuntimeScope(ref: ActorRef): ActorScope =
        runtimeScopes[ref] ?: throw ActorSystemException("Actor[$ref] runtime not found")

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(ActorSystemImpl::class.java)
    }
}
