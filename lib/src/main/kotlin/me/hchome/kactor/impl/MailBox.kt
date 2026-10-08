package me.hchome.kactor.impl

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import me.hchome.kactor.MailboxClosedException
import me.hchome.kactor.MailboxFullException
import me.hchome.kactor.MessagePriority

/**
 * An actor's mailbox: one system control channel plus two user priority channels.
 *
 * - [controlChannel] is unbounded and never drops; it carries system envelopes such as
 *   [ActorEnvelope.SuperviseEnvelope] so they are never subject to user flow control.
 * - [highPriorityChannel] / [lowPriorityChannel] each hold up to `capacity` user envelopes and
 *   apply `onBufferOverflow` when full.
 *
 * Every envelope that is accepted by [offer] / [offerControl] is either received by the actor
 * or handed to [onReject] exactly once — on overflow, when the mailbox is [cancel]led with
 * envelopes still buffered, or when offered to an already cancelled mailbox.
 *
 * @param capacity buffer size of **each** user priority channel; must be positive
 * @param onBufferOverflow what to do when a user channel is full:
 *   [BufferOverflow.SUSPEND] and [BufferOverflow.DROP_LATEST] reject the new envelope,
 *   [BufferOverflow.DROP_OLDEST] rejects the oldest buffered envelope to make room
 * @param onReject called with every envelope that will never be delivered, and the reason.
 *   Must be fast, non-blocking and must not throw.
 */
class MailBox(
    capacity: Int,
    onBufferOverflow: BufferOverflow,
    private val onReject: (ActorEnvelope, Throwable) -> Unit,
) {
    init {
        require(capacity > 0) { "Mailbox capacity must be positive, was $capacity" }
    }

    @Volatile
    private var cancelled = false

    // Called by the channel for envelopes dropped by DROP_OLDEST or left in the buffer on cancel().
    private val onUndelivered: (ActorEnvelope) -> Unit = { envelope ->
        onReject(envelope, if (cancelled) MailboxClosedException() else MailboxFullException())
    }

    // trySend under DROP_LATEST reports success while silently dropping the element, so
    // DROP_LATEST is implemented as SUSPEND + rejecting the failed trySend ourselves.
    private val userOverflow =
        if (onBufferOverflow == BufferOverflow.DROP_LATEST) BufferOverflow.SUSPEND else onBufferOverflow

    private val _control: Channel<ActorEnvelope> = Channel(Channel.UNLIMITED, onUndeliveredElement = onUndelivered)
    private val _high: Channel<ActorEnvelope> = Channel(capacity, userOverflow, onUndelivered)
    private val _low: Channel<ActorEnvelope> = Channel(capacity, userOverflow, onUndelivered)

    internal val controlChannel: ReceiveChannel<ActorEnvelope> get() = _control
    internal val highPriorityChannel: ReceiveChannel<ActorEnvelope> get() = _high
    internal val lowPriorityChannel: ReceiveChannel<ActorEnvelope> get() = _low

    /**
     * Offers a user envelope without suspending.
     * @return `true` if it was enqueued; `false` if it was rejected via [onReject]
     */
    fun offer(envelope: ActorEnvelope, priority: MessagePriority): Boolean = when (priority) {
        MessagePriority.HIGH -> offer(_high, envelope)
        MessagePriority.NORMAL -> offer(_low, envelope)
    }

    /**
     * Offers a system envelope. The control channel is unbounded, so this only fails
     * (and rejects via [onReject]) once the mailbox is cancelled.
     */
    fun offerControl(envelope: ActorEnvelope): Boolean = offer(_control, envelope)

    private fun offer(channel: Channel<ActorEnvelope>, envelope: ActorEnvelope): Boolean {
        val result = channel.trySend(envelope)
        if (result.isSuccess) return true
        // trySend never calls onUndeliveredElement, so reject here.
        onReject(envelope, if (result.isClosed) MailboxClosedException() else MailboxFullException())
        return false
    }

    /**
     * Cancels all channels; every envelope still buffered is rejected via [onReject].
     */
    fun cancel() {
        cancelled = true
        _control.cancel()
        _high.cancel()
        _low.cancel()
    }
}