package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.hchome.kactor.ActorContext
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Implementation of [ActorContext] that provides access to the actor system and actor's context.
 * Open BaseActor's CoroutineScope to use it in the actor handler's methods.
 */
internal data class ActorContextImpl(
    private val self: BaseActor,
    private val system: ActorSystem,
    private val dispatcher: CoroutineDispatcher,
    val actorJob: CompletableJob
) : ActorContext,
    Attributes by AttributesImpl() {

    val actorScope = CoroutineScope(actorJob + dispatcher)

    override fun getService(kClass: KClass<out ActorHandler>): ActorRef = system.getService(kClass)

    override val services: Set<ActorRef>
        get() = system.getServices()

    override val ref: ActorRef
        get() = self.ref

    override val parent: ActorRef
        get() = self.ref.parentOf()

    override val children: Set<ActorRef>
        get() = system.childReferences(self.ref)

    override fun <T : ActorHandler> sendService(kClass: KClass<out T>, message: Any) {
        val ref = ActorRef.ofService(kClass)
        if (ref !in system) {
            system.notifySystem(
                self.ref, ActorRef.EMPTY,
                "Target service $kClass is not found: $message",
                ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION
            )
            return
        }
        system.send(ref, self.ref, message)
    }

    override fun hasActor(ref: ActorRef): Boolean = ref in system

    override fun sendChildren(message: Any) {
        if (self.singleton) {
            system.notifySystem(
                self.ref, ActorRef.EMPTY,
                "Target children shouldn't be a service: $message",
                ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION
            )
            return
        }
        if (children.isEmpty()) {
            system.notifySystem(
                self.ref, ActorRef.EMPTY,
                "Send a message to an empty children: $message",
                ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED
            )
            return
        }
        children.forEach {
            system.send(it, self.ref, message)
        }
    }

    override fun getChild(id: String): ActorRef {
        return system.childReferences(self.ref).firstOrNull { it.name.contentEquals(id) } ?: ActorRef.EMPTY
    }

    override fun sendChild(childRef: ActorRef, message: Any) {
        if (self.singleton) {
            return
        }
        children.firstOrNull { it == childRef }?.also {
            system.send(it, self.ref, message)
        } ?: run {
            system.notifySystem(
                self.ref, ActorRef.EMPTY,
                "Send a message to an empty child $childRef: $message",
                ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED
            )
        }
    }

    override fun sendParent(message: Any) {
        if (self.ref.hasParent) {
            system.send(self.ref.parentOf(), self.ref, message)
        } else {
            system.notifySystem(
                self.ref, ActorRef.EMPTY,
                "Send a message to an empty parent: $message",
                ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED
            )
        }
    }

    override fun stopActor(ref: ActorRef) {
        system.destroyActor(ref)
    }

    override fun sendSelf(message: Any) {
        system.send(self.ref, self.ref, message)
    }

    override fun stopChild(childRef: ActorRef) {
        system.destroyActor(childRef)
    }

    override fun stopSelf() {
        system.destroyActor(self.ref)
    }


    override fun stopChildren() {
        children.forEach {
            system.destroyActor(it)
        }
    }

    override suspend fun <T : ActorHandler> newChild(
        id: String?,
        kClass: KClass<T>,
    ): ActorRef {
        if (self.singleton) throw ActorSystemException("Can't create a child for a singleton actor")
        return system.actorOfSuspend(id, self.ref, kClass)
    }

    override suspend fun <T : ActorHandler> newActor(
        id: String?,
        kClass: KClass<T>
    ): ActorRef {
        return system.actorOfSuspend(id, ActorRef.EMPTY, kClass)
    }

    override fun schedule(
        period: Duration,
        initDelay: Duration,
        block: suspend ActorHandler.(String) -> Unit
    ): Job {
        return self.schedule(period, initDelay, block)
    }

    override fun task(
        initDelay: Duration,
        block: suspend ActorHandler.(String) -> Unit
    ): Job {
        return self.task(initDelay, block)
    }

    override fun sendActor(ref: ActorRef, message: Any) {
        try {
            system.send(ref, self.ref, message)
        } catch (e: IllegalStateException) {
            system.notifySystem(
                self.ref, ActorRef.EMPTY,
                "Send a message to an empty actor $ref: $message",
                ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED
            )
            throw ActorSystemException("Send a message to an empty actor $ref: $message", e)
        }
    }

    override fun <T : Any> ask(message: Any, ref: ActorRef, timeout: Duration): Deferred<T> {
        if (ref == self.ref) {
            throw ActorSystemException("Can't ask self")
        } else if (ref == ActorRef.EMPTY) {
            throw ActorSystemException("Can't ask empty actor")
        }
        if (ref in system) {
            return system.ask(ref, self.ref, message)
        } else {
            throw ActorSystemException("Actor not in system")
        }
    }

    override suspend fun <T : ActorHandler> newService(kClass: KClass<T>): ActorRef = system.serviceOfSuspend(kClass)

    override fun launch(
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ): Job = actorScope.launch(start = start, block = block)

    override fun <T> async(
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = actorScope.async(start = start, block = block)

    override fun <T> share(
        flow: Flow<T>,
        started: SharingStarted,
        replay: Int
    ): SharedFlow<T> = flow.shareIn(actorScope, started, replay)

    override fun <T> state(
        flow: Flow<T>,
        stated: SharingStarted,
        initValue: T
    ): StateFlow<T> = flow.stateIn(actorScope, stated, initValue)

    override suspend fun <T> state(flow: Flow<T>): StateFlow<T> = flow.stateIn(actorScope)
}