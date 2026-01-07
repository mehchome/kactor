@file:Suppress("unused")

package me.hchome.kactor.impl

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.hchome.kactor.ActorFailure
import me.hchome.kactor.ActorHandler
import me.hchome.kactor.ActorRef
import me.hchome.kactor.ActorSystem
import me.hchome.kactor.ActorSystemNotificationMessage
import me.hchome.kactor.Attributes
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
 * An actor is a business logic object that can receive messages and send messages to other actors.
 * @see ActorSystem
 */
internal class Actor(
    val ref: ActorRef,
    val singleton: Boolean,
    private val actorSystem: ActorSystem,
    private val supervisorStrategy: SupervisorStrategy,
    private val supervisor: Supervisor,
    private val mailbox: Channel<ActorEnvelope>,
    private val runtimeScope: ActorScope,
    private val handler: ActorHandler,
    private val attributes: Attributes
) : Supervisor {

    private val context = ActorContextImpl(this, actorSystem, runtimeScope, attributes)

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


    fun send(message: Any, sender: ActorRef) {
        val result = mailbox.trySend(ActorEnvelope.SendActorEnvelope(message, sender))
        if (result.isFailure) {
            // TODO: notify system
        }
    }

    fun <T : Any> ask(message: Any, sender: ActorRef, callback: CompletableDeferred<in T>) {
        val result = mailbox.trySend(ActorEnvelope.AskActorEnvelope(message, sender, callback))
        if (result.isFailure) {
            // TODO: notify system
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

    @Suppress("UNCHECKED_CAST")
    fun startActor() {
        mailBoxJob = runtimeScope.launch {
            context(context) {
                try {
                    handler.preStart()
                    for (msg in mailbox) {
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
                        } catch (e: CancellationException) {
                            LOGGER.debug("Actor cancelled: {}", ref, e)
                        } catch (e: Throwable) {
                            supervisor.supervise(ref, sender, message, e)
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        handler.postStop()
                    }
                }
            }
        }
    }

    override suspend fun supervise(
        child: ActorRef,
        sender: ActorRef,
        message: Any,
        cause: Throwable
    ) {
        supervisorStrategy.onFailure(ActorFailure(actorSystem, child, sender, message, cause))
    }

    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(Actor::class.java)
    }
}
