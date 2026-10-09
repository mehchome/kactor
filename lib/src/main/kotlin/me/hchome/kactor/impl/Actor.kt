@file:Suppress("unused")

package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import me.hchome.kactor.ActorFailure
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
import me.hchome.kactor.FailureCause
import me.hchome.kactor.MessagePriority
import me.hchome.kactor.SupervisorStrategy
import me.hchome.kactor.TaskInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi

private data class SupervisionMessage(
    val failure: ActorFailure,
    val deferred: CompletableDeferred<SupervisorStrategy.Decision>
)

/**
 * An actor is a business logic object that can receive messages and send messages to other actors.
 * @see ActorSystem
 */
class Actor internal constructor(
    val ref: ActorRef,
    val domain: String,
    private val actorSystem: ActorSystem,
    private val supervisorStrategy: SupervisorStrategy,
    private val mailbox: MailBox,
    private val runtimeScope: ActorScope,
    private val handler: ActorHandler,
    attributes: Attributes,
    private val idle: Duration,
) {

    private val context = ActorContextImpl(this, actorSystem, runtimeScope, attributes)

    // Unbounded so that a failing child never blocks when reporting to its parent.
    private val supervisionChannel = Channel<SupervisionMessage>(Channel.UNLIMITED)

    private var mailBoxJob: Job? = null

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
     * Routes a child supervision request into this actor's supervision channel
     * so that [ActorHandler.supervise] is invoked within this actor's own coroutine context.
     * Called by the ActorSystem — not by child actors directly.
     */
    internal fun submitSupervision(
        failure: ActorFailure,
        callback: CompletableDeferred<SupervisorStrategy.Decision>,
    ) {
        // Channel is UNLIMITED so trySend always succeeds.
        supervisionChannel.trySend(SupervisionMessage(failure, callback))
    }

    fun send(message: Any, sender: ActorRef, priority: MessagePriority = MessagePriority.NORMAL) {
        runtimeScope.launch {
            mailbox.send(ActorEnvelope.SendActorEnvelope(message, sender), priority)
        }
    }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    fun startActor() {
        mailBoxJob = runtimeScope.launch {
            val receiveChannel = with(mailbox) { this@launch.selectMailbox() }
            context(context) {
                try {

                    handler.preStart()
                    var running = true
                    while (running && isActive) {
                        // Supervision messages take priority: drain before entering select.
                        supervisionChannel.tryReceive().getOrNull()?.let { msg ->
                            val decision = handler.supervise(msg.failure.ref, msg.failure.cause)
                            val actual = supervisorStrategy.handle(msg.failure, decision)
                            msg.deferred.complete(actual)
                            return@let
                        }
                        running = select {
                            supervisionChannel.onReceiveCatching { result ->
                                val msg = result.getOrNull() ?: return@onReceiveCatching true
                                val decision = handler.supervise(msg.failure.ref, msg.failure.cause)
                                val actual = supervisorStrategy.handle(msg.failure, decision)
                                msg.deferred.complete(actual)
                                true
                            }
                            receiveChannel.onReceiveCatching { result ->
                                val msg = result.getOrNull()
                                if (msg == null) {
                                    false
                                } else {
                                    val message = msg.message
                                    val sender = msg.sender
                                    try {
                                        when (msg) {
                                            is ActorEnvelope.SendActorEnvelope -> handler.onMessage(message, sender)
                                            is ActorEnvelope.AskActorEnvelope<*> -> handler.onAsk(
                                                message,
                                                sender,
                                                msg.callback as CompletableDeferred<in Any>
                                            )
                                        }
                                        true
                                    } catch (e: Throwable) {
                                        val failure = ActorFailure(
                                            actorSystem, ref, sender, message, FailureCause.of(e)
                                        )
                                        val decision = try {
                                            actorSystem.reportFailure(ref, failure)
                                        } catch (_: Exception) {
                                            SupervisorStrategy.Decision.Stop
                                        }
                                        when (decision) {
                                            SupervisorStrategy.Decision.Resume -> {
                                                if (msg is ActorEnvelope.AskActorEnvelope<*>) {
                                                    msg.callback.completeExceptionally(e)
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                }
                            }
                            onTimeout(idle) {
                                handler.onIdle()
                                true
                            }
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

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(Actor::class.java)
    }
}
