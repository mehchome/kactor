@file:Suppress("unused")
package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
import me.hchome.kactor.Props
import me.hchome.kactor.Supervisor
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.TaskInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

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
 * @param props startup parameters this actor was spawned (or recreated) with
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
    props: Props = Props.EMPTY,
) : Supervisor {

    private val context = ActorContextImpl(this, actorSystem, runtimeScope, attributes, props)

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
     * The envelope is offered to the mailbox synchronously, so messages from the same caller
     * keep their order. If the mailbox rejects it (full or closed), it is reported as
     * [ActorSystemNotificationMessage.NotificationType.MESSAGE_UNDELIVERED].
     *
     * @param message payload to deliver to [ActorHandler.onMessage]
     * @param sender originating actor reference, or [ActorRef.EMPTY] if anonymous
     * @param priority [MessagePriority.HIGH] messages are processed before [MessagePriority.NORMAL] ones
     */
    fun send(message: Any, sender: ActorRef, priority: MessagePriority = MessagePriority.NORMAL) {
        mailbox.offer(ActorEnvelope.SendActorEnvelope(message, sender), priority)
    }

    /**
     * Enqueues a request-reply message into the mailbox.
     *
     * The [callback] deferred is completed by [ActorHandler.onAsk] once the handler produces
     * a reply. If the actor fails while processing the message and the supervisor decides
     * [SupervisorStrategy.Decision.Resume], the callback is completed exceptionally.
     * If the mailbox rejects the message, the callback is completed exceptionally with
     * [me.hchome.kactor.MailboxFullException] or [me.hchome.kactor.MailboxClosedException].
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
        mailbox.offer(ActorEnvelope.AskActorEnvelope(message, sender, callback), priority)
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
     * [ActorEnvelope.SuperviseEnvelope] and placed on the mailbox's **control** channel, which is
     * unbounded and read before any user message, so it is never dropped or delayed by user traffic.
     * If this actor's mailbox is already cancelled, the request is rejected with
     * [SupervisorStrategy.Decision.Stop].
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
        mailbox.offerControl(ActorEnvelope.SuperviseEnvelope(failure, callback))
        return callback.await()
    }

    /**
     * Starts the actor's mailbox processing loop.
     *
     * Launches a coroutine in [runtimeScope] that:
     * 1. Calls [ActorHandler.preStart].
     * 2. Drains the mailbox in order control → high → low via `tryReceive`, falling back to a
     *    `select` only when all are empty; fires [ActorHandler.onIdle] (finite [idle] only)
     *    whenever [idle] elapses without a message.
     * 3. Handles [ActorEnvelope.SuperviseEnvelope] from the control channel inline (bypassing
     *    [dispatch]) so that supervision decisions do not interfere with normal message flow.
     * 4. Exits the loop when the mailbox is cancelled or a handler signals a fatal decision.
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
                val idleEnabled = idle.isFinite()
                try {
                    handler.preStart()
                    while (true) {
                        // tryReceive never checks cancellation, so a busy mailbox must do it here.
                        ensureActive()
                        // Fast path: drain already-queued messages without allocating a select.
                        val control = mailbox.controlChannel.tryReceive()
                        if (control.isSuccess) {
                            if (!processControl(control.getOrThrow())) break
                            continue
                        }
                        val high = mailbox.highPriorityChannel.tryReceive()
                        if (high.isSuccess) {
                            if (!dispatch(high.getOrThrow())) break
                            continue
                        }
                        val low = mailbox.lowPriorityChannel.tryReceive()
                        if (low.isSuccess) {
                            if (!dispatch(low.getOrThrow())) break
                            continue
                        }
                        // Channels are only ever cancelled together, with the whole mailbox.
                        if (control.isClosed || high.isClosed || low.isClosed) break
                        // Slow path: mailbox empty, suspend until a message arrives or idle elapses.
                        val keepRunning = select {
                            mailbox.controlChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { processControl(it) } ?: false
                            }
                            mailbox.highPriorityChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { dispatch(it) } ?: false
                            }
                            mailbox.lowPriorityChannel.onReceiveCatching { result ->
                                result.getOrNull()?.let { dispatch(it) } ?: false
                            }
                            if (idleEnabled) {
                                onTimeout(idle) {
                                    handler.onIdle()
                                    true
                                }
                            }
                        }
                        if (!keepRunning) break
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
    /**
     * Handles an envelope from the control channel: supervision requests are handled
     * inline, anything else goes through [dispatch].
     */
    context(ctx: ActorContext)
    private suspend fun processControl(msg: ActorEnvelope): Boolean = when (msg) {
        is ActorEnvelope.SuperviseEnvelope -> handleSupervision(msg)
        else -> dispatch(msg)
    }

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
     * If supervision itself throws, the callback is completed with
     * [SupervisorStrategy.Decision.Restart] as a safe fallback, and the exception is treated as
     * this actor's own failure: it is reported to [supervisor], just like a failure in [dispatch].
     *
     * @param envelope the supervision request, carrying the [ActorFailure] and the callback
     *   deferred that the failing child is awaiting
     * @return `true` to keep the loop running; `false` when this actor's own supervisor
     *   decided anything other than [SupervisorStrategy.Decision.Resume]
     */
    context(ctx: ActorContext)
    private suspend fun handleSupervision(envelope: ActorEnvelope.SuperviseEnvelope): Boolean {
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
                return true
            }

            applyDecision(failure.ref, decision)
            envelope.callback.complete(decision)
            return true
        } catch (e: Throwable) {
            if (!envelope.callback.isCompleted) {
                envelope.callback.complete(SupervisorStrategy.Decision.Restart)
            }
            if (e is CancellationException) throw e
            actorSystem.notifySystem(
                failure.sender, ref, "Supervision handling failed: ${e.message}",
                ActorSystemNotificationMessage.NotificationType.ACTOR_EXCEPTION, e
            )
            // A broken supervisor is itself a failed actor: let its own supervisor decide.
            val ownFailure = ActorFailure(ref, failure.sender, failure, e)
            return supervisor.supervise(ownFailure) == SupervisorStrategy.Decision.Resume
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