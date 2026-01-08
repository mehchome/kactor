package me.hchome.kactor

import kotlinx.coroutines.CompletableDeferred

/**
 * Actor handler - business logic for an actor
 */
interface ActorHandler {

    context(context: ActorContext)
    val ref: ActorRef
        get() = context.ref

    /**
     * Ask handler
     */
    context(context: ActorContext)
    suspend fun onAsk(message: Any, sender: ActorRef, callback: CompletableDeferred<in Any>) {
    }

    /**
     * Receive handler
     */
    context(context: ActorContext)
    suspend fun onMessage(message: Any, sender: ActorRef) {
    }

    /**
     * Task exception handler
     */
    context(context: ActorContext)
    fun onTaskException(taskInfo: TaskInfo, exception: Throwable) {
    }

    /**
     * Before the actor receiving and processing messages
     */
    context(context: ActorContext)
    suspend fun preStart() {
    }

    /**
     * After the actor stopped receiving messages
     */
    context(context: ActorContext)
    suspend fun postStop() {
    }

    /**
     * actor idle over set timeout
     */
    context(context: ActorContext)
    suspend fun onIdle() {}
}
