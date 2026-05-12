package me.hchome.kactor.impl

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import me.hchome.kactor.MessagePriority

class MailBox(
    capacity: Int = Channel.RENDEZVOUS,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    onUndeliveredMessage: ((ActorEnvelope) -> Unit)? = null
) {
    private val _high: Channel<ActorEnvelope> = Channel(capacity, onBufferOverflow, onUndeliveredMessage)
    private val _low: Channel<ActorEnvelope> = Channel(capacity, onBufferOverflow, onUndeliveredMessage)

    internal val highPriorityChannel: ReceiveChannel<ActorEnvelope> get() = _high
    internal val lowPriorityChannel: ReceiveChannel<ActorEnvelope> get() = _low

    suspend fun send(envelope: ActorEnvelope, priority: MessagePriority) = when (priority) {
        MessagePriority.HIGH -> _high.send(envelope)
        MessagePriority.NORMAL -> _low.send(envelope)
    }

    fun close() {
        _low.close()
        _high.close()
    }
}
