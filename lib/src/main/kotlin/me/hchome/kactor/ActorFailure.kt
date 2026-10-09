package me.hchome.kactor

/**
 * Serializable representation of a failure cause for cross-node transmission.
 * Identified by [code] rather than exception class name, so it can travel
 * across nodes where the original exception class may not exist.
 *
 * Use [ActorException] in business handlers to provide a meaningful [code].
 * Non-[ActorException] throwables are mapped to [UNKNOWN_CODE].
 */
data class FailureCause(
    val code: String,
    val message: String?,
    val stackTrace: List<String> = emptyList(),
) {
    companion object {
        const val UNKNOWN_CODE = "ACTOR_UNKNOWN_ERROR"

        fun of(e: Throwable): FailureCause = FailureCause(
            code = if (e is ActorException) e.code else UNKNOWN_CODE,
            message = e.message,
            stackTrace = e.stackTrace.map { it.toString() },
        )
    }

    override fun toString() = buildString {
        append(code)
        if (message != null) append(": $message")
        stackTrace.take(5).forEach { append("\n\tat $it") }
        if (stackTrace.size > 5) append("\n\t... ${stackTrace.size - 5} more")
    }
}

/**
 * Actor failure message
 */
data class ActorFailure(
    val system: ActorSystem,
    val ref: ActorRef,
    val sender: ActorRef,
    val message: Any,
    val cause: FailureCause,
)
