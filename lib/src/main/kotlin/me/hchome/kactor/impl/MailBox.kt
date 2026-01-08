package me.hchome.kactor.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import me.hchome.kactor.MessagePriority

data class MailBox(
    private val capacity: Int = Channel.RENDEZVOUS,
    private val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    private val onUndeliveredMessage: ((ActorEnvelope) -> Unit)? = null
) {

    private val highPriorityChannel = Channel(capacity, onBufferOverflow, onUndeliveredMessage)
    private val lowPriorityChannel = Channel(capacity, onBufferOverflow, onUndeliveredMessage)

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun CoroutineScope.selectMailbox(): ReceiveChannel<ActorEnvelope> = produce {
        var highOpen = true
        var lowOpen = true

        while (highOpen || lowOpen) {
            select {

                if (highOpen) {
                    highPriorityChannel.onReceiveCatching { result ->
                        val v = result.getOrNull()
                        if (v == null) {
                            highOpen = false
                        } else {
                            send(v)
                        }
                    }
                }

                if (lowOpen) {
                    lowPriorityChannel.onReceiveCatching { result ->
                        val v = result.getOrNull()
                        if (v == null) {
                            lowOpen = false
                        } else {
                            send(v)
                        }
                    }
                }
            }
        }
    }

    fun trySend(envelope: ActorEnvelope, priority: MessagePriority) = when (priority) {
        MessagePriority.HIGH -> highPriorityChannel.trySend(envelope)
        MessagePriority.NORMAL -> lowPriorityChannel.trySend(envelope)
    }

    fun close() {
        lowPriorityChannel.close()
        highPriorityChannel.close()
    }
}