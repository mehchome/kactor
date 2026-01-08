@file:Suppress("unused")

package me.hchome.kactor

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import me.hchome.kactor.impl.ActorSystemImpl
import me.hchome.kactor.impl.LocalActorRegistry
import kotlin.reflect.KClass

/**
 * An actor system is a container for actors. It is responsible for creating, destroying, and sending messages to actors.
 */
@JvmDefaultWithCompatibility
interface ActorSystem : ActorHandlerRegistry {

    /**
     * create an actor
     * @param id actor id
     * @param parent parent actor reference
     * @param kClass actor handler class
     * @return actor reference
     */
    fun <T> actorOf(
        id: String? = null,
        parent: ActorRef = ActorRef.EMPTY,
        kClass: KClass<T>
    ): ActorRef where T : ActorHandler = runBlocking {
        actorOfSuspend(id, parent, kClass)
    }

    /**
     * create an actor
     * @param id actor id
     * @param parent parent actor reference
     * @return actor reference
     */
    suspend fun <T> actorOfSuspend(
        id: String? = null,
        parent: ActorRef = ActorRef.EMPTY,
        kClass: KClass<T>
    ): ActorRef where T : ActorHandler

    /**
     * destroy an actor
     * @param actorRef actor reference
     */
    fun destroyActor(actorRef: ActorRef)

    /**
     * send a message to an actor
     * @param actorRef actor reference
     * @param sender sender actor reference
     * @param message message
     */
    fun send(actorRef: ActorRef, sender: ActorRef, message: Any, priority: MessagePriority = MessagePriority.NORMAL)

    /**
     * send a message to an actor
     * @param actorRef actor reference
     * @param message message
     */
    fun send(actorRef: ActorRef, message: Any, priority: MessagePriority = MessagePriority.NORMAL) =
        send(actorRef, ActorRef.EMPTY, message, priority)


    /**
     * ask a status from an actor
     * @param actorRef actor reference
     * @param sender sender actor reference
     * @param message message
     * @param timeout timeout
     * @return deferred result
     */
    fun <T : Any> ask(
        actorRef: ActorRef,
        sender: ActorRef,
        message: Any,
        priority: MessagePriority = MessagePriority.NORMAL,
    ): Deferred<T>


    fun notifySystem(
        sender: ActorRef,
        receiver: ActorRef,
        message: String,
        notificationType: ActorSystemNotificationMessage.NotificationType,
        throwable: Throwable? = null
    )

    suspend fun processFailure(ref: ActorRef, decision: SupervisorStrategy.Decision)

    /**
     * shutdown actor system gracefully
     */
    fun shutdownGracefully()

    /**
     * start actor system
     */
    fun start()

    /**
     * get child actor references
     */
    fun childReferences(parent: ActorRef): Set<ActorRef>

    /**
     * check if an actor exists
     */
    operator fun contains(ref: ActorRef): Boolean

    /**
     * add message listener to the actor system
     */
    fun addListener(listener: ActorSystemMessageListener)

    /**
     * add message listener to the actor system
     */
    operator fun plusAssign(listener: ActorSystemMessageListener) = addListener(listener)

    companion object {

        /**
         * Create an actor system
         *
         * <p>
         *     Planned to support a clustering actor system in the future.
         * </p>
         *
         * @param factory actor handler factory
         * @return actor system
         */
        @JvmStatic
        fun createOrGet(
            factory: ActorHandlerFactory = DefaultActorHandlerFactory,
            strategy: SupervisorStrategy = SupervisorStrategy.OneForOne,
            registry: ActorRegistry = LocalActorRegistry()
        ): ActorSystem = ActorSystemImpl(factory, strategy, registry)
    }
}

suspend inline fun <reified T> ActorSystem.actorOfSuspend(
    id: String? = null,
    parent: ActorRef = ActorRef.EMPTY
): ActorRef where T : ActorHandler =
    actorOfSuspend(id, parent, T::class)

inline fun <reified T> ActorSystem.actorOf(
    id: String? = null,
    parent: ActorRef = ActorRef.EMPTY
): ActorRef where T : ActorHandler =
    actorOf(id, parent, T::class)
