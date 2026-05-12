package me.hchome.kactor

/**
 * Represents a supervisor in an actor-based system. A supervisor is responsible for managing
 * the lifecycle of its child actors and handling any failures that occur during their execution.
 */
interface Supervisor {
    /**
     * Handle a child actor failure described by [failure].
     * Returns the [SupervisorStrategy.Decision] that was applied.
     */
    suspend fun supervise(failure: ActorFailure): SupervisorStrategy.Decision
}