package me.hchome.kactor

/**
 * Actor handler - business logic for an actor
 */
interface ActorHandler {

    context(context: ActorContext)
    val ref: ActorRef
        get() = context.ref

    /**
     * Ask handler: return the reply. The framework completes the ask with the returned value, or
     * fails it with a thrown exception (which is also supervised like any handler failure), so an
     * ask can never be left unanswered.
     *
     * The default throws [AskNotHandledException], which fails the ask without supervising the actor.
     */
    context(context: ActorContext)
    suspend fun onAsk(message: Any, sender: ActorRef): Any =
        throw AskNotHandledException(context.ref, this::class.qualifiedName)

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
     * Before the actor receiving and processing messages. If it throws, the failure is reported to
     * the supervisor as an [ActorInitializationException]; the actor only starts processing messages
     * if the decision is [SupervisorStrategy.Decision.Resume].
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
     * The default decision is [SupervisorStrategy.Decision.Restart], except for a failed
     * [preStart] ([ActorInitializationException]), which is [SupervisorStrategy.Decision.Stop]:
     * restarting would usually fail again, in a loop.
     *
     * To decide by failure type, throw an [ActorException] from the child and match its code:
     * ```kotlin
     * override suspend fun onSupervise(failure: ActorFailure) = when (failure.code) {
     *     "PAYMENT_DECLINED" -> SupervisorStrategy.Decision.Resume
     *     ActorInitializationException.CODE -> SupervisorStrategy.Decision.Stop
     *     else -> SupervisorStrategy.Decision.Restart
     * }
     * ```
     */
    context(context: ActorContext)
    suspend fun onSupervise(failure: ActorFailure): SupervisorStrategy.Decision =
        if (failure.cause is ActorInitializationException) SupervisorStrategy.Decision.Stop
        else SupervisorStrategy.Decision.Restart
}