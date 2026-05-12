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
     * Actor idle over set timeout
     */
    context(context: ActorContext)
    suspend fun onIdle() {}

    /**
     * Called on the *failing* actor when it throws during message handling.
     * Override to enrich the failure report before it is sent to the supervisor.
     * The default builds an [ActorFailure] from the current context.
     */
    context(context: ActorContext)
    suspend fun onException(message: Any, sender: ActorRef, cause: Throwable): ActorFailure =
        ActorFailure(context.ref, sender, message, cause)

    /**
     * Called on the *supervisor* actor when one of its children fails.
     * Return the [SupervisorStrategy.Decision] that should be applied.
     * The scope (OneForOne vs AllForOne) is determined by the supervisor's [ActorConfig].
     *
     * The default decision is [SupervisorStrategy.Decision.Restart].
     */
    context(context: ActorContext)
    suspend fun onSupervise(failure: ActorFailure): SupervisorStrategy.Decision =
        SupervisorStrategy.Decision.Restart
}