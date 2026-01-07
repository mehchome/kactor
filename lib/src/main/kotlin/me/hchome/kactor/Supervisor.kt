package me.hchome.kactor

/**
 * Represents a supervisor in an actor-based system. A supervisor is responsible for managing the lifecycle
 * of its child actors and handling any failures that occur during their execution.
 */
interface Supervisor {
    /**
     * Handles the failure of the child actor.
     * @param child The child actor that failed
     * @param cause The cause of the failure
     */
    suspend fun supervise(
        child: ActorRef,
        sender: ActorRef,
        message: Any,
        cause: Throwable,
    )
}