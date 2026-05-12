@file:Suppress("unused")

package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.whileSelect
import kotlinx.coroutines.withContext
import me.hchome.kactor.ActorContext
import me.hchome.kactor.BehaviorBlock
import me.hchome.kactor.ActorFailure
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.MessagePriority
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.TaskInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi

private typealias ActorHandlerScope = suspend ActorHandler.(Any, ActorRef) -> Unit
private typealias AskActorHandlerScope = suspend ActorHandler.(Any, ActorRef, CompletableDeferred<in Any>) -> Unit

/**
 * Runtime representation of a single actor instance.
 *
 * An [Actor] owns a two-priority [MailBox] and a dedicated [ActorScope] (coroutine scope)
 * that are created by [me.hchome.kactor.ActorRegistry] and kept alive for as long as the
 * actor is running. All state—mailbox, scope, handler, attributes—is replaced atomically
 * when the actor is restarted or recreated.
 *
 * Lifecycle:
 * 1. [startActor] — launches the mailbox loop coroutine; calls [ActorHandler.preStart].
 * 2. Message loop — runs [ActorHandler.onMessage] / [ActorHandler.onAsk] per envelope;
 *    supervision requests are handled inline via [handleSupervision].
 * 3. Stop — the mailbox channels are closed or the scope is cancelled; the loop exits and
 *    [ActorHandler.postStop] is called under [NonCancellable] to guarantee completion.
 *
 * Supervision:
 * An [Actor] implements [Supervisor] so that its children can route [ActorFailure]s to it.
 * The failure arrives as a [ActorEnvelope.SuperviseEnvelope] in the high-priority channel,
 * ensuring it is processed before any pending normal messages.
 *
 * @param ref unique identifier and position in the actor hierarchy
 * @param domain the registered handler domain name used for configuration lookup
 * @param actorSystem the owning actor system; used for routing and notification
 * @param supervisorStrategy scope applied when a child fails ([SupervisorStrategy.OneForOne]
 *   or [SupervisorStrategy.AllForOne])
 * @param supervisor the [Supervisor] of this actor (its parent actor or the actor system)
 * @param mailbox two-priority mailbox ([MailBox.highPriorityChannel] / [MailBox.lowPriorityChannel])
 * @param runtimeScope coroutine scope in which the mailbox loop and tasks execute
 * @param handler business-logic delegate; receives all lifecycle and message callbacks
 * @param attributes mutable key-value store attached to this actor instance
 * @param idle after this duration without a message [ActorHandler.onIdle] is invoked
 *
 * @see ActorSystem
 * @see ActorHandler
 * @see SupervisorStrategy
 */
class Actor internal constructor(
    val ref: ActorRef,
    val domain: String,
    private val actorSystem: ActorSystem,
    private val supervisorStrategy: SupervisorStrategy,
    private val supervisor: Supervisor,
    private val mailbox: MailBox,
    private val runtimeScope: ActorScope,
    private val handler: ActorHandler,
    attributes: Attributes,
    private val idle: Duration,
) : Supervisor {

    private val context = ActorContextImpl(this, actorSystem, runtimeScope, attributes)

    private var mailBoxJob: Job? = null

    /**
     * Exception handler attached to every background [task] and [schedule] coroutine.
     * Notifies the system and delegates to [ActorHandler.onTaskException] so the handler
     * can react without crashing the mailbox loop.
     */
    private val taskExceptionHandler = CoroutineExceptionHandler { ctx, e ->
        actorSystem.notifySystem(
            ref, ref, "Exception occurred [${ref}] task: $e",
            ActorSystemNotificationMessage.NotificationType.ACTOR_TASK_EXCEPTION, e
        )
        val info = ctx[TaskInfo] ?: return@CoroutineExceptionHandler
        context(context) {
            handler.onTaskException(info, e)
        }
    }

    /**
     * Enqueues a fire-and-forget message into the mailbox.
     *
     * The send is non-blocking from the caller's perspective: the envelope is placed on the
     * mailbox channel inside a coroutine launched on [runtimeScope].
     *
     * @param message payload to deliver to [ActorHandler.onMessage]
     * @param sender originating actor reference, or [ActorRef.EMPTY] if anonymous
     * @param priority [MessagePriority.HIGH] messages are processed before [MessagePriority.NORMAL] ones
     */
    fun send(message: Any, sender: ActorRef, priority: MessagePriority = MessagePriority.NORMAL) {
        runtimeScope.launch {
            mailbox.send(ActorEnvelope.SendActorEnvelope(message, sender), priority)
        }
    }

    /**
     * Enqueues a request-reply message into the mailbox.
     *
     * The [callback] deferred is completed by [ActorHandler.onAsk] once the handler produces
     * a reply. If the actor fails while processing the message and the supervisor decides
     * [SupervisorStrategy.Decision.Resume], the callback is completed exceptionally.
     *
     * @param T expected reply type
     * @param message payload to deliver to [ActorHandler.onAsk]
     * @param sender originating actor reference, or [ActorRef.EMPTY] if anonymous
     * @param callback deferred that will be completed with the handler's reply
     * @param priority message priority; defaults to [MessagePriority.NORMAL]
     */
    fun <T : Any> ask(
        message: Any,
        sender: ActorRef,
        callback: CompletableDeferred<in T>,
        priority: MessagePriority = MessagePriority.NORMAL
    ) {
        runtimeScope.launch {
            mailbox.send(ActorEnvelope.AskActorEnvelope(message, sender, callback), priority)
        }
    }

    /**
     * Launches a one-shot background task coroutine within this actor's scope.
     *
     * The task runs independently of the mailbox loop. Uncaught exceptions are reported
     * to [ActorHandler.onTaskException] via [taskExceptionHandler] and do not stop the actor.
     *
     * @param initDelay optional delay before the block executes
     * @param block suspending lambda that receives the task's auto-generated id
     * @return the [Job] for the launched coroutine
     */
    @OptIn(ExperimentalUuidApi::class)
    fun task(
        initDelay: Duration = Duration.ZERO,
        block: suspend ActorHandler.(String) -> Unit
    ): Job {
        val taskInfo = TaskInfo.Task(initDelay, block)
        return runtimeScope.launch(taskInfo + taskExceptionHandler) {
            delay(initDelay)
            block(handler, taskInfo.id)
        }
    }

    /**
     * Launches a recurring background task coroutine within this actor's scope.
     *
     * After an optional [initDelay] the [block] is executed repeatedly, pausing [period]
     * between each invocation. Uncaught exceptions are reported to
     * [ActorHandler.onTaskException] and do not stop the actor.
     *
     * @param period time between consecutive executions
     * @param initDelay optional delay before the first execution
     * @param block suspending lambda that receives the schedule's auto-generated id
     * @return the [Job] for the launched coroutine; cancel it to stop the schedule
     */
    @OptIn(ExperimentalUuidApi::class)
    fun schedule(
        period: Duration,
        initDelay: Duration = Duration.ZERO,
        block: suspend ActorHandler.(String) -> Unit
    ): Job {
        val taskInfo = TaskInfo.Schedule(period, initDelay, block)
        return runtimeScope.launch(taskInfo + taskExceptionHandler) {
            delay(initDelay)
            while (isActive) {
                block(handler, taskInfo.id)
                delay(period)
            }
        }
    }

    /**
     * Routes a child failure to this actor for supervision processing.
     *
     * Implements [Supervisor] so that child actors can call this method when they catch
     * an unhandled exception in their mailbox loop. The [failure] is wrapped in a
     * [ActorEnvelope.SuperviseEnvelope] and placed on the **high-priority** channel so it
     * is processed before any pending normal messages.
     *
     * This method suspends until [handleSupervision] completes and the
     * [SupervisorStrategy.Decision] is determined by [ActorHandler.onSupervise].
     *
     * @param failure description of the child failure, including the failing actor's ref,
     *   the message that triggered it, and the cause
     * @return the decision that was applied to the failing child (and possibly its siblings)
     */
    override suspend fun supervise(failure: ActorFailure): SupervisorStrategy.Decision {
        val callback = CompletableDeferred<SupervisorStrategy.Decision>()
        mailbox.send(ActorEnvelope.SuperviseEnvelope(failure, callback), MessagePriority.HIGH)
        return callback.await()
    }

    /**
     * Starts the actor's mailbox processing loop.
     *
     * Launches a coroutine in [runtimeScope] that:
     * 1. Calls [ActorHandler.preStart].
     * 2. Enters a `whileSelect` loop that drains both priority channels—high before low—and
     *    fires [ActorHandler.onIdle] whenever [idle] elapses without a message.
     * 3. Handles [ActorEnvelope.SuperviseEnvelope] inline (bypassing [dispatch]) so that
     *    supervision decisions do not interfere with normal message flow.
     * 4. Exits the loop when both channels close or [dispatch] signals a fatal decision.
     * 5. Calls [ActorHandler.postStop] under [NonCancellable] to guarantee cleanup even
     *    when the coroutine is cancelled externally.
     *
     * This method is idempotent in the sense that each call replaces [mailBoxJob]; the
     * registry always cancels the old scope before calling [startActor] again on restart.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    fun startActor() {
        mailBoxJob = runtimeScope.launch {
            context(context) {
                var highOpen = true
                var lowOpen = true
                try {
                    handler.preStart()
                    whileSelect {
                        if (highOpen) {
                            mailbox.highPriorityChannel.onReceiveCatching { result ->
                                val msg = result.getOrNull()
                                if (msg == null) {
                                    highOpen = false
                                    lowOpen
                                } else {
                                    when (msg) {
                                        is ActorEnvelope.SuperviseEnvelope -> {
                                            handleSupervision(msg)
                                            true
                                        }
                                        else -> dispatch(msg).also {
                                            if (!it) { highOpen = false; lowOpen = false }
                                        }
                                    }
                                }
                            }
                        }
                        if (lowOpen) {
                            mailbox.lowPriorityChannel.onReceiveCatching { result ->
                                val msg = result.getOrNull()
                                if (msg == null) {
                                    lowOpen = false
                                    highOpen
                                } else {
                                    dispatch(msg).also {
                                        if (!it) { highOpen = false; lowOpen = false }
                                    }
                                }
                            }
                        }
                        onTimeout(idle) {
                            handler.onIdle()
                            true
                        }
                    }
                } catch (e: CancellationException) {
                    LOGGER.debug("Actor cancelled: {}", ref)
                    throw e
                } finally {
                    withContext(NonCancellable) {
                        handler.postStop()
                    }
                }
            }
        }
    }

    /**
     * Dispatches a single [ActorEnvelope] to the appropriate handler method.
     *
     * Returns `true` to continue the mailbox loop, or `false` to signal that the loop
     * should exit (when the supervisor decides anything other than [SupervisorStrategy.Decision.Resume]).
     *
     * On exception:
     * 1. [ActorHandler.onException] is called on this actor's handler to produce an [ActorFailure].
     *    If `onException` itself throws, a default [ActorFailure] is constructed from the original cause.
     * 2. The [ActorFailure] is forwarded to the supervisor via [supervisor].
     * 3. [SupervisorStrategy.Decision.Resume] → loop continues (`true`); the ask callback, if
     *    present, is completed exceptionally.
     * 4. Any other decision → loop exits (`false`); the registry will restart or stop this actor.
     *
     * @param msg the envelope dequeued from the mailbox
     * @return `true` to keep the loop running; `false` to exit
     */
    @Suppress("UNCHECKED_CAST")
    context(ctx: ActorContext)
    private suspend fun dispatch(msg: ActorEnvelope): Boolean {
        val message = msg.message
        val sender = msg.sender
        return try {
            when (msg) {
                is ActorEnvelope.SendActorEnvelope -> {
                    val behavior: BehaviorBlock? = this.context.currentBehavior
                    if (behavior != null) handler.behavior(ctx, message, sender)
                    else handler.onMessage(message, sender)
                }
                is ActorEnvelope.AskActorEnvelope<*> -> handler.onAsk(
                    message, sender, msg.callback as CompletableDeferred<in Any>
                )
                is ActorEnvelope.SuperviseEnvelope -> {} // handled separately in startActor
            }
            true
        } catch (e: Throwable) {
            val failure = try {
                handler.onException(message, sender, e)
            } catch (_: Throwable) {
                ActorFailure(ref, sender, message, e)
            }
            when (supervisor.supervise(failure)) {
                SupervisorStrategy.Decision.Resume -> {
                    if (msg is ActorEnvelope.AskActorEnvelope<*>) msg.callback.completeExceptionally(e)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Processes a supervision request received from a failing child actor.
     *
     * Runs inside this actor's mailbox loop (in the supervisor's [ActorContext]):
     * 1. Fires an [ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL] notification.
     * 2. Calls [ActorHandler.onSupervise] to obtain the [SupervisorStrategy.Decision].
     * 3. If the decision is [SupervisorStrategy.Decision.Escalate], re-packages the failure
     *    with this actor's own [ref] and delegates upward to [supervisor]; the decision
     *    returned by the grandparent is passed back to the failing child.
     * 4. Otherwise, [applyDecision] translates the decision to an actor-system command,
     *    respecting the [supervisorStrategy] scope (OneForOne vs AllForOne).
     * 5. Completes the [ActorEnvelope.SuperviseEnvelope.callback] so that the child's
     *    suspended [supervise] call can resume.
     *
     * If [ActorHandler.onSupervise] throws, the callback is completed with
     * [SupervisorStrategy.Decision.Restart] as a safe fallback before the exception propagates.
     *
     * @param envelope the supervision request, carrying the [ActorFailure] and the callback
     *   deferred that the failing child is awaiting
     */
    context(ctx: ActorContext)
    private suspend fun handleSupervision(envelope: ActorEnvelope.SuperviseEnvelope) {
        val failure = envelope.failure
        actorSystem.notifySystem(
            failure.sender, failure.ref, "Actor[${failure.ref}] failure",
            ActorSystemNotificationMessage.NotificationType.ACTOR_FATAL, failure.cause
        )
        try {
            val decision = handler.onSupervise(failure)

            if (decision == SupervisorStrategy.Decision.Escalate) {
                // Pass failure upward as if this supervisor itself failed.
                val escalated = failure.copy(ref = ref)
                val escalatedDecision = supervisor.supervise(escalated)
                envelope.callback.complete(escalatedDecision)
                return
            }

            applyDecision(failure.ref, decision)
            envelope.callback.complete(decision)
        } catch (e: Throwable) {
            if (!envelope.callback.isCompleted) {
                envelope.callback.complete(SupervisorStrategy.Decision.Restart)
            }
            if (e !is CancellationException) {
                actorSystem.notifySystem(
                    failure.sender, ref, "Supervision handling failed: ${e.message}",
                    ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION, e
                )
            }
            throw e
        }
    }

    /**
     * Translates a [SupervisorStrategy.Decision] into an actor-system command and applies it
     * according to the configured [supervisorStrategy] scope.
     *
     * - [SupervisorStrategy.OneForOne]: the decision is applied only to [failedRef].
     * - [SupervisorStrategy.AllForOne]: the decision is applied to every current child of
     *   this actor (including the failing one).
     *
     * @param failedRef the [ActorRef] of the child that triggered the failure
     * @param decision the action to take, as returned by [ActorHandler.onSupervise]
     */
    private suspend fun applyDecision(failedRef: ActorRef, decision: SupervisorStrategy.Decision) {
        when (supervisorStrategy) {
            SupervisorStrategy.OneForOne -> actorSystem.processFailure(failedRef, decision)
            SupervisorStrategy.AllForOne -> actorSystem.childReferences(ref).forEach {
                actorSystem.processFailure(it, decision)
            }
        }
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(Actor::class.java)
    }
}