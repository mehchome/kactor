@file:Suppress("unused")

package me.hchome.kactor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


/**
 * Actor context is a container for actor information and methods. Use the context to do the operations
 * on the actor. Implements [CoroutineScope] to allow the actor to run coroutines inside the actor context.
 * So when the actor is stopped, all coroutines inside the context will be cancelled.
 *
 * @see ActorContext
 * @see ActorHandler
 */
interface ActorContext : Attributes {

    /**
     * launch suspend block in actor scope
     */
    fun launch(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> Unit): Job

    /**
     * launch suspend block in actor scope and return callback when it was completed
     */
    fun <T> async(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend CoroutineScope.() -> T): Deferred<T>

    /**
     * convert a flow to a hot shared flow
     */
    fun <T> share(flow: Flow<T>, started: SharingStarted, replay: Int = 0): SharedFlow<T>

    /**
     * convert a flow to a hot state flow
     */
    fun <T> state(flow: Flow<T>, stated: SharingStarted, initValue: T): StateFlow<T>

    /**
     * convert a state to a hot flow
     */
    suspend fun <T> state(flow: Flow<T>): StateFlow<T>

    /**
     * Actor reference
     * @see ActorRef
     */
    val ref: ActorRef

    /**
     * Parent actor reference
     * @see ActorRef
     */
    val parent: ActorRef

    /**
     * Children actor references
     * @see ActorRef
     */
    val children: Set<ActorRef>

    /**
     * Check if an actor has a parent
     * @see ActorRef
     */
    val hasParent: Boolean get() = parent.isNotEmpty()

    /**
     * Check if an actor has children
     * @see ActorRef
     */
    val hasChildren: Boolean get() = children.isNotEmpty()

    /**
     * Check if an actor has a child
     * @see ActorRef
     */
    operator fun contains(childRef: ActorRef): Boolean = children.contains(childRef)


    /**
     * Check system has the reference
     */
    fun hasActor(ref: ActorRef): Boolean

    /**
     * Check if an actor has a service
     * @see ActorRef
     */
    fun hasService(kClass: KClass<out ActorHandler>): Boolean = getService(kClass).isNotEmpty()

    /**
     * Get a service actor reference
     * @see ActorRef
     */
    fun getService(kClass: KClass<out ActorHandler>): ActorRef

    /**
     * Check if an actor is a child of target actor
     * @see ActorRef
     */
    fun isChild(childRef: ActorRef): Boolean = children.contains(childRef)

    /**
     * Check if an actor used to be a child of target actor
     * @see ActorRef
     */
    fun isFormalChild(childRef: ActorRef): Boolean {
        return !isChild(childRef) && childRef.isNotEmpty() && childRef.actorId.isNotEmpty()
                && childRef.actorId.startsWith(ref.actorId)
    }

    /**
     * Check if an actor is a parent of target actor
     * @see ActorRef
     */
    fun isParent(parentRef: ActorRef): Boolean {
        return parentRef.isParentOf(ref)
    }

    /**
     * Send a message to a service actor
     */
    fun <T : ActorHandler> sendService(kClass: KClass<out T>, message: Any)

    /**
     * Send a message to any actor
     */
    fun sendActor(ref: ActorRef, message: Any)

    /**
     * Send a message to all children actors
     */
    fun sendChildren(message: Any)

    /**
     * Send a message to a child actor
     */
    fun sendChild(childRef: ActorRef, message: Any)

    /**
     * Get a child actor reference
     * @see ActorRef
     */
    fun getChild(id: String): ActorRef

    /**
     * Send a message to the parent actor
     */
    fun sendParent(message: Any)

    /**
     * Send a message to the self-actor
     */
    fun sendSelf(message: Any)

    /**
     * Send a message to the actor (not self) and wait for a response
     */
    fun <T : Any> ask(message: Any, ref: ActorRef, timeout: Duration = Duration.INFINITE): Deferred<T>

    /**
     * Stop a child actor
     */
    fun stopChild(childRef: ActorRef)

    /**
     * Stop the self-actor
     */
    fun stopSelf()

    /**
     * Stop all children actors
     */
    fun stopChildren()

    /**
     * Stop an actor with its reference
     */
    fun stopActor(ref: ActorRef)

    /**
     * restart an actor
     */

    /**
     * Create a child actor
     * @see ActorRef
     */
    suspend fun <T> newChild(
        id: String? = null,
        kClass: KClass<T>,
    ): ActorRef where T : ActorHandler

    /**
     * Create a new actor
     * @see ActorRef
     */
    suspend fun <T> newActor(
        id: String? = null,
        kClass: KClass<T>,
    ): ActorRef where T : ActorHandler


    suspend fun <T> newService(kClass: KClass<T>): ActorRef where T : ActorHandler

    /**
     * Schedule a task
     */
    fun schedule(
        period: Duration,
        initDelay: Duration = Duration.ZERO,
        block: suspend ActorHandler.(String) -> Unit
    ): Job

    /**
     * create a run task
     */
    fun task(initDelay: Duration = Duration.ZERO, block: suspend ActorHandler.(String) -> Unit): Job
}

suspend inline fun <reified T : ActorHandler> ActorContext.newChild(id: String? = null): ActorRef {
    return newChild(id, T::class)
}

suspend inline fun <reified T : ActorHandler> ActorContext.newActor(id: String? = null): ActorRef {
    return newActor(id, T::class)
}

suspend inline fun <reified T : ActorHandler> ActorContext.newService(): ActorRef = newService(T::class)

inline fun <reified T : ActorHandler> ActorContext.sendService(message: Any) = sendService(T::class, message)

inline fun <reified T : ActorHandler> ActorContext.getService() = getService(T::class)

/**
 * High function proxy launch in coroutine scope to prevent coroutine accidentally leaked
 */
fun <T> Flow<T>.launchIn(context: ActorContext): Job {
    return context.launch { collect() }
}

/**
 * High function proxy share in coroutine scope to prevent coroutine accidentally leaked
 */
fun <T> Flow<T>.shareIn(context: ActorContext, started: SharingStarted, replay: Int = 0): Flow<T> =
    context.share(this, started, replay)


/**
 * Task type
 */
enum class TaskType {
    TASK, SCHEDULE
}

/**
 * Task information
 */
sealed class TaskInfo @OptIn(ExperimentalUuidApi::class) constructor(
    val type: TaskType,
    val id: String = Uuid.random().toHexString()
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    class Task(
        val initDelay: Duration,
        val block: suspend ActorHandler.(String) -> Unit
    ) : TaskInfo(TaskType.TASK)

    class Schedule(
        val initDelay: Duration,
        val period: Duration,
        val block: suspend ActorHandler.(String) -> Unit
    ) : TaskInfo(TaskType.SCHEDULE)

    companion object Key : CoroutineContext.Key<TaskInfo>
}


/**
 * Actor configuration
 * @see Actor
 */
data class ActorConfig(
    val capacity: Int = 100,
    val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.OneForOne
) {
    companion object {
        @JvmStatic
        val DEFAULT = ActorConfig()
    }
}