package me.hchome.kactor

/**
 * Base exception for actor business logic failures.
 * Carry an [code] so that [FailureCause] can identify the failure type
 * without relying on the exception class name, enabling cross-node transmission.
 */
open class ActorException(
    val code: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
