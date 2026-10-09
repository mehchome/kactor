package me.hchome.kactor

/**
 * Describes a child actor failure, passed from the failing actor to its supervisor
 * via [ActorHandler.onException] and then to [ActorHandler.onSupervise].
 */
data class ActorFailure(
    val ref: ActorRef,
    val sender: ActorRef,
    val message: Any,
    val cause: Throwable,
    val reason: String = cause.message ?: "Unknown failure"
) {
    /** [ActorException.code] of [cause], or [UNKNOWN_CODE] when [cause] is not an [ActorException] */
    val code: String get() = (cause as? ActorException)?.code ?: UNKNOWN_CODE

    companion object {
        const val UNKNOWN_CODE = "ACTOR_UNKNOWN_ERROR"
    }
}