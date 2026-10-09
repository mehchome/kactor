@file:Suppress("unused")

package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.hchome.kactor.ActorFailure
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorHandlerFactory
import me.hchome.kactor.ActorHandlerRegistry
import me.hchome.kactor.ActorInitializationException
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorRegistry
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemMessageListener
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.MessagePriority
import me.hchome.kactor.Props
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.SystemMessage
import me.hchome.kactor.SystemMessage.*
import me.hchome.kactor.UserMessage
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Actor system implementation
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ActorSystemImpl(
    handlerFactory: ActorHandlerFactory,
    private val supervisorStrategy: SupervisorStrategy,
    private val actorRegistry: ActorRegistry
) : ActorSystem,
    Supervisor,
    ActorHandlerRegistry by ActorHandlerRegistryImpl(Dispatchers.Default, handlerFactory) {
    private val systemJob = SupervisorJob()
    private val systemScope = CoroutineScope(systemJob + Dispatchers.Default)
    private val systemMailbox: Channel<SystemMessage> = Channel(512)

    val all: Set<ActorRef> get() = actorRegistry.all

    private var mailboxJob: Job? = null

    private val listeners = mutableListOf<ActorSystemMessageListener>()

    private val runningFlag = AtomicBoolean(false)

    init {
        actorRegistry.afterInit(this, systemJob, this)
    }


    override fun contains(ref: ActorRef): Boolean = actorRegistry.contains(ref)

    override fun addListener(listener: ActorSystemMessageListener) {
        listeners.add(listener)
    }

    override fun childReferences(parent: ActorRef): Set<ActorRef> = actorRegistry.childReferences(parent)

    override suspend fun processFailure(
        ref: ActorRef,
        decision: SupervisorStrategy.Decision
    ) {
        when (decision) {
            SupervisorStrategy.Decision.Resume -> {}
            SupervisorStrategy.Decision.Stop -> systemMailbox.send(StopActor(ref))
            SupervisorStrategy.Decision.Recreate -> systemMailbox.send(RestartActor(ref, true))
            SupervisorStrategy.Decision.Restart -> systemMailbox.send(RestartActor(ref, false))
            SupervisorStrategy.Decision.Escalate -> systemMailbox.send(RestartActor(ref, false))
        }
    }

    override suspend fun <T : ActorHandler> actorOfSuspend(
        id: String?,
        parent: ActorRef,
        kClass: KClass<T>,
        props: Props
    ): ActorRef {
        val domain = this.findName(kClass) ?: throw ActorSystemException("Actor handler type not found: $kClass")
        return actorOfSuspend(id ?: Uuid.random().toString(), parent, domain, props)
    }

    private suspend fun actorOfSuspend(
        id: String,
        parent: ActorRef,
        domain: String,
        props: Props,
    ): ActorRef {
        if (!runningFlag.load()) {
            throw ActorSystemException("Actor system not running")
        }
        val deferred = CompletableDeferred<ActorRef>()
        return withTimeout(1.minutes) {
            systemMailbox.send(CreateActor(id, parent, domain, deferred, props))
            deferred.await()
        }
    }

    override fun start() {
        mailboxJob?.cancel()
        mailboxJob = systemScope.launch {
            try {
                for (message in systemMailbox) {
                    handleSystemMessage(message)
                }
            } catch (e: CancellationException) {
                LOGGER.debug("Actor system mailboxes job cancelled")
                throw e
            }
        }
        runningFlag.compareAndExchange(expectedValue = false, newValue = true)
    }

    override fun shutdownGracefully() {
        systemMailbox.close()
        mailboxJob?.cancel()
        actorRegistry.stopAllActors()
        systemJob.cancel()
        systemScope.cancel()
        notifySystem(ActorRef.EMPTY, ActorRef.EMPTY, "Actor system shutdown", ActorSystemNotificationMessage.NotificationType.SYSTEM_CLOSE)
    }

    override fun destroyActor(actorRef: ActorRef) {
        systemScope.launch {
            try {
                systemMailbox.send(StopActor(actorRef))
            } catch(_: CancellationException) {
                LOGGER.debug("Actor system mailboxes job cancelled")
            } catch (e: Throwable) {
                notifySystem(actorRef, ActorRef.EMPTY, e.message ?: "Actor destroy failed", ActorSystemNotificationMessage.NotificationType.SYSTEM_ERROR)
            }
        }
    }

    override fun send(actorRef: ActorRef, sender: ActorRef, message: Any, priority: MessagePriority) {
        if (!runningFlag.load()) {
            throw ActorSystemException("Actor system not running")
        }
        actorRegistry.tell(UserMessage.Tell(actorRef, sender, message, priority))
    }


    override fun <T : Any> ask(
        actorRef: ActorRef,
        sender: ActorRef,
        message: Any,
        priority: MessagePriority,
    ): Deferred<T> {
        if (!runningFlag.load()) {
            throw ActorSystemException("Actor system not running")
        }
        val deferred = CompletableDeferred<T>()
        actorRegistry.ask(UserMessage.Ask(actorRef, sender, message, priority, deferred))
        return deferred
    }

    override fun notifySystem(
        sender: ActorRef,
        receiver: ActorRef,
        message: String,
        notificationType: ActorSystemNotificationMessage.NotificationType,
        throwable: Throwable?,
        data: Any?
    ) {
        val level = when (notificationType) {
            ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED,
            ActorSystemNotificationMessage.NotificationType.ACTOR_TIMEOUT,
            ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION,
            ActorSystemNotificationMessage.NotificationType.ACTOR_TASK_EXCEPTION
                -> ActorSystemNotificationMessage.MessageLevel.WARN

            ActorSystemNotificationMessage.NotificationType.ACTOR_MESSAGE,
            ActorSystemNotificationMessage.NotificationType.ACTOR_CREATED,
            ActorSystemNotificationMessage.NotificationType.ACTOR_RESTARTED,
            ActorSystemNotificationMessage.NotificationType.SYSTEM_CLOSE,
            ActorSystemNotificationMessage.NotificationType.ACTOR_DESTROYED -> ActorSystemNotificationMessage.MessageLevel.INFO

            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL -> ActorSystemNotificationMessage.MessageLevel.ERROR
            ActorSystemNotificationMessage.NotificationType.SYSTEM_ERROR -> ActorSystemNotificationMessage.MessageLevel.ERROR
        }
        val notification = ActorSystemNotificationMessage(notificationType, sender, receiver, level, message, data,throwable)
        listeners.forEach { listener ->
            listener.onMessage(notification)
        }
    }

    override suspend fun supervise(failure: ActorFailure): SupervisorStrategy.Decision {
        notifySystem(
            failure.sender, failure.ref, "Actor[${failure.ref}] failure [${failure.code}]",
            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, failure.cause
        )
        // A failed preStart would most likely fail again on restart: stop just that actor.
        if (failure.cause is ActorInitializationException) {
            processFailure(failure.ref, SupervisorStrategy.Decision.Stop)
            return SupervisorStrategy.Decision.Stop
        }
        // System is the root supervisor — always restarts; Escalate has nowhere to go.
        val decision = SupervisorStrategy.Decision.Restart
        when (supervisorStrategy) {
            SupervisorStrategy.OneForOne -> processFailure(failure.ref, decision)
            SupervisorStrategy.AllForOne -> all.filter { !it.hasParent }.forEach {
                processFailure(it, decision)
            }
        }
        return decision
    }

    private fun handleSystemMessage(message: SystemMessage): Unit = try {
        when (message) {
            is CreateActor -> actorRegistry.createActor(message)
            is StopActor -> actorRegistry.stopActor(message.ref)
            is RestartActor -> actorRegistry.restartActor(message.ref, message.recreate)
        }
    } catch (e: Throwable) {
        notifySystem(
            ActorRef.EMPTY,
            ActorRef.EMPTY,
            e.message ?: "Actor system exception",
            ActorSystemNotificationMessage.NotificationType.SYSTEM_ERROR,
            e
        )
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(ActorSystemImpl::class.java)
    }
}
