@file:Suppress("unused")

package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorHandlerConfigHolder
import me.hchome.kactor.ActorHandlerFactory
import me.hchome.kactor.ActorHandlerRegistry
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorRegistry
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.SupervisorStrategy.AllForOne
import me.hchome.kactor.SupervisorStrategy.Backoff
import me.hchome.kactor.SupervisorStrategy.Escalate
import me.hchome.kactor.SupervisorStrategy.OneForOne
import me.hchome.kactor.SupervisorStrategy.Resume
import me.hchome.kactor.SupervisorStrategy.Stop
import me.hchome.kactor.SystemMessage
import me.hchome.kactor.SystemMessage.*
import me.hchome.kactor.UserMessage
import me.hchome.kactor.isEmpty
import me.hchome.kactor.isNotEmpty
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
) : ActorSystem,
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

    private var mailboxJob: Job? = null

    internal val _notifications = MutableSharedFlow<ActorSystemNotificationMessage>(0, 64, BufferOverflow.DROP_OLDEST)
    override val notifications: Flow<ActorSystemNotificationMessage>
        get() = _notifications

//    override fun dispose(): Unit = runBlocking {
//        systemJob.cancelAndJoin()
//        clear()
//        systemScope.cancel()
//    }

    override fun contains(ref: ActorRef): Boolean = actorRegistry.contains(ref)

    override fun childReferences(parent: ActorRef): Set<ActorRef> = actorRegistry.childReferences(parent)

    override suspend fun processFailure(
        ref: ActorRef,
        sender: ActorRef,
        message: Any,
        strategy: SupervisorStrategy
    ) {

    }

    override suspend fun <T : ActorHandler> actorOfSuspend(
        id: String?,
        parent: ActorRef,
        kClass: KClass<T>
    ): ActorRef = actorOfSuspend(id, false, parent, kClass)

    override suspend fun <T> serviceOfSuspend(
        kClass: KClass<T>
    ): ActorRef where T : ActorHandler = actorOfSuspend(null, true, ActorRef.Companion.EMPTY, kClass)

    override fun getServices(): Set<ActorRef> {
        return all().filter { it.singleton }.map { it.ref }.toSet()
    }

    override fun getService(kClass: KClass<out ActorHandler>): ActorRef {
        return all().firstOrNull {
            it.ref.actorId == "$kClass"
        }?.ref ?: ActorRef.EMPTY
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
    }

    suspend fun supervise(
        child: ActorRef,
        singleton: Boolean,
        cause: Throwable
    ) {
        val actor = this[child]
        if (LOGGER.isDebugEnabled) {
            LOGGER.debug("Supervise actor ${child.actorId} with restart strategy ${supervisorStrategy.javaClass.simpleName}")
        }
        when (supervisorStrategy) {
            is OneForOne, is AllForOne, is Escalate -> {
                val attributes = snapshot(child)
                destroyActor(child)
                val new = if (singleton) {
                    serviceOfSuspend(actor.handlerClass)
                } else {
                    actorOfSuspend(child.name, ActorRef.EMPTY, actor.handlerClass)
                }
                if (attributes != null) {
                    recover(new, attributes)
                }
            }

            is Resume -> {}
            is Stop -> destroyActor(child)
            is Backoff -> {
                val (init, max) = supervisorStrategy
                val attributes = snapshot(child)
                delay(init)
                destroyActor(child)
                delay(max)
                val new = if (singleton) {
                    serviceOfSuspend(actor.handlerClass)
                } else {
                    actorOfSuspend(child.name, ActorRef.EMPTY, actor.handlerClass)
                }
                if (attributes != null) {
                    recover(new, attributes)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() = runBlocking {
        mailboxJob?.cancelAndJoin()
        mailboxJob = systemScope.launch {
            try {
                while (true) {
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
        clear()
        systemScope.cancel()
    }

    private fun <T> actorOfSuspend(
        id: String?,
        singleton: Boolean,
        parent: ActorRef,
        kClass: KClass<T>
    ): ActorRef where T : ActorHandler {
        val parentActor = if (parent.isNotEmpty()) this[parent] else null
        val actorId = buildActorId(parent, id, singleton, kClass)

        val config = this[kClass]
        val actorDispatcher = config.dispatcher
        if (parentActor != null && parentActor.singleton) {
            throw ActorSystemException("Parent actor is a singleton actor")
        }

        val ref = ActorRef(actorId)
        if (contains(ref)) {
            if (singleton) {
                return ref
            } else {
                throw ActorSystemException("Actor with id $actorId already exists")
            }
        }

        val actor = createActor(
            actorDispatcher, kClass, config.factory, this@ActorSystemImpl, actorId, config.config,
            singleton, systemJob, parentActor as Actor
        )
        this[actor.ref] = actor
        this@ActorSystemImpl.notifySystem(
            actor.ref, ActorRef.EMPTY, "Actor created",
            ActorSystemNotificationMessage.NotificationType.ACTOR_CREATED
        )
        return actor.ref
    }

    override fun destroyActor(actorRef: ActorRef) {
        this.remove(actorRef)?.dispose()
        notifySystem(
            actorRef,
            ActorRef.EMPTY,
            "Actor destroyed",
            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED
        )
    }

    override fun send(actorRef: ActorRef, sender: ActorRef, message: Any) {
        this[actorRef].send(message, sender)
    }

    override fun <T : Any> ask(actorRef: ActorRef, sender: ActorRef, message: Any, timeout: Duration): Deferred<T> =
        systemScope.async {
            val actor = this@ActorSystemImpl[actorRef]
            val deferred = CompletableDeferred<T>()
            try {
                withTimeout(timeout) {
                    actor.ask<T>(message, sender, deferred)
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                notifySystem(
                    sender,
                    actorRef,
                    "Actor timeout",
                    ActorSystemNotificationMessage.NotificationType.ACTOR_TIMEOUT,
                )
                throw ActorSystemException("Actor timeout")
            }
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
        return if (!parent.isEmpty()) {
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
        val level = when (notificationType) {
            ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED,
            ActorSystemNotificationMessage.NotificationType.ACTOR_TIMEOUT,
            ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION,
            ActorSystemNotificationMessage.NotificationType.ACTOR_TASK_EXCEPTION
                -> ActorSystemNotificationMessage.MessageLevel.WARN

            ActorSystemNotificationMessage.NotificationType.ACTOR_MESSAGE,
            ActorSystemNotificationMessage.NotificationType.ACTOR_CREATED,
            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED -> ActorSystemNotificationMessage.MessageLevel.INFO

            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL -> ActorSystemNotificationMessage.MessageLevel.ERROR
        }
        val notification = ActorSystemNotificationMessage(sender, receiver, level, message, throwable)
        _notifications.tryEmit(notification)
    }

    private suspend fun handleSystemMessage(message: SystemMessage): Unit = when (message) {
        is CreateActor -> handleCreateActor(message)

    }

    private suspend fun handleUserMessage(message: UserMessage): Unit = when (message) {
        else -> {}
    }

    private suspend fun handleCreateActor(message: CreateActor) {
        val (ref, isSingleton, domain) = message
        val config = this[domain]
        val parentJob = if(ref.hasParent) {

        }


    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(ActorSystemImpl::class.java)
    }
}
