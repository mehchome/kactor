package me.hchome.kactor

/**
 * Actor failure message
 */
data class ActorFailure(
    val system: ActorSystem,
    val ref: ActorRef,
    val sender: ActorRef,
    val message: Any,
    val cause: Throwable,
    val supervisor: Supervisor
)
