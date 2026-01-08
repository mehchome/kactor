package me.hchome.kactor.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import me.hchome.kactor.ActorContext
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemException
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.MessagePriority
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Implementation of [ActorContext] that provides access to the actor system and actor's context.
 * Open BaseActor's CoroutineScope to use it in the actor handler's methods.
 */
internal data class ActorContextImpl(
    private val self: Actor,
    private val system: ActorSystem,
    private val runtimeScope: ActorScope,
    private val attributes: Attributes
) : ActorContext, Attributes by attributes {


    override fun getService(kClass: KClass<out ActorHandler>): ActorRef = system.getService(kClass)


    override val ref: ActorRef
        get() = self.ref

    override val parent: ActorRef
        get() = self.ref.parentOf()

    override val children: Set<ActorRef>
        get() = system.childReferences(self.ref)

    override fun <T : ActorHandler> sendService(kClass: KClass<out T>, message: Any, priority: MessagePriority) {
        val ref = ActorRef.ofService(kClass)
        system.send(ref, self.ref, message, priority)
    }

    override fun sendChildren(message: Any, priority: MessagePriority) {
        children.forEach {
            system.send(it, self.ref, message, priority)
        }
    }

    override fun getChild(id: String): ActorRef {
        return system.childReferences(self.ref).firstOrNull { it.name.contentEquals(id) } ?: ActorRef.EMPTY
    }

    override fun sendChild(childRef: ActorRef, message: Any, priority: MessagePriority) {
        children.firstOrNull { it == childRef }.also {
            system.send(it ?: ActorRef.EMPTY, self.ref, message, priority)
        }
    }

    override fun sendParent(message: Any, priority: MessagePriority) {
        system.send(self.ref.parentOf(), self.ref, message, priority)
    }

    override fun stopActor(ref: ActorRef) {
        system.destroyActor(ref)
    }

    override fun sendSelf(message: Any, priority: MessagePriority) {
        system.send(self.ref, self.ref, message, priority)
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
    ): Job = self.schedule(period, initDelay, block)

    override fun task(
        initDelay: Duration,
        block: suspend ActorHandler.(String) -> Unit
    ): Job = self.task(initDelay, block)

    override fun sendActor(ref: ActorRef, message: Any, priority: MessagePriority) {
        system.send(ref, self.ref, message, priority)
    }

    override fun <T : Any> ask(message: Any, ref: ActorRef, priority: MessagePriority): Deferred<T> {
        if (ref == self.ref) {
            throw ActorSystemException("Can't ask self")
        } else if (ref == ActorRef.EMPTY) {
            throw ActorSystemException("Can't ask empty actor")
        }
        if (ref in system) {
            return system.ask(ref, self.ref, message, priority)
        } else {
            throw ActorSystemException("Actor not in system")
        }
    }

    override suspend fun <T : ActorHandler> newService(kClass: KClass<T>): ActorRef = system.serviceOfSuspend(kClass)

    override fun launch(
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit
    ): Job = runtimeScope.launch(started = start, block = block)

    override fun <T> async(
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> T
    ): Deferred<T> = runtimeScope.async(started = start, block = block)

    override fun <T> share(
        flow: Flow<T>,
        started: SharingStarted,
        replay: Int
    ): SharedFlow<T> = runtimeScope.share(flow, started, replay)

    override fun <T> state(
        flow: Flow<T>,
        stated: SharingStarted,
        initValue: T
    ): StateFlow<T> = runtimeScope.state(flow, stated, initValue)

    override suspend fun <T> state(flow: Flow<T>): StateFlow<T> = runtimeScope.state(flow)
}