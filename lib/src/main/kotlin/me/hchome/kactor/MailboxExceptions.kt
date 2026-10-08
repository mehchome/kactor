package me.hchome.kactor

/**
 * A message was rejected because the target actor's mailbox was full
 * (see [ActorConfig.capacity] and [ActorConfig.onBufferOverflow]).
 * Stack traces are omitted: these are flow-control signals, not bugs.
 */
class MailboxFullException : IllegalStateException("Mailbox full") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * A message was rejected because the target actor's mailbox was closed
 * (the actor was stopped or recreated before the message was processed).
 * Stack traces are omitted: these are flow-control signals, not bugs.
 */
class MailboxClosedException : IllegalStateException("Mailbox closed") {
    override fun fillInStackTrace(): Throwable = this
}
