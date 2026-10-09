package me.hchome.kactor

/**
 * Base exception for actor business failures that carries a [code] identifying the failure type.
 *
 * Throw it (or a subclass) from a handler so the supervisor can decide by [ActorFailure.code]
 * in [ActorHandler.onSupervise] instead of by exception class.
 */
open class ActorException(
    val code: String,
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)